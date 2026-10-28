package vn.pnj.meal.api.org;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.pnj.meal.api.access.SystemAdminBootstrap;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.common.ApiErrorCode;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.db.SqlTime;
import vn.pnj.meal.api.dmp.DmpClient;
import vn.pnj.meal.api.registrations.ForecastService;
import vn.pnj.meal.api.settings.SettingKey;
import vn.pnj.meal.api.settings.SettingsService;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.org.DmpTree;

/**
 * Plan P1 / section 5: syncs the org tree and employees from DMP (daily job and manual trigger).
 * Every run writes a SyncRun. Data is written only when the whole run succeeds: DMP is read and
 * validated first, then everything is written in one transaction.
 *
 * <p>Admin-owned data is never overwritten: meal-unit flags and floor / canteen / cost-center
 * mapping stay as the HC admin set them (BR-60, BR-62). Nodes gone from DMP are deactivated, not
 * deleted (rule 7).
 */
@Service
public class OrgSyncService {
  private static final Logger log = LoggerFactory.getLogger(OrgSyncService.class);

  /** Postgres advisory-lock key, so two org syncs never write at the same time. */
  static final long SYNC_LOCK_KEY = 4_100_001;

  /** A run that died mid-way stays RUNNING; after this long it no longer blocks new runs. */
  private static final Duration STALE_RUN = Duration.ofMinutes(30);

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final JsonMapper json;
  private final ClockService clock;
  private final AuditService audit;
  private final SystemAdminBootstrap bootstrap;
  private final SettingsService settings;
  private final MealUnits mealUnits;
  private final ForecastService forecast;
  private final DmpClient dmp;

  public OrgSyncService(
      JdbcClient jdbc,
      TransactionTemplate tx,
      JsonMapper json,
      ClockService clock,
      AuditService audit,
      SystemAdminBootstrap bootstrap,
      SettingsService settings,
      MealUnits mealUnits,
      ForecastService forecast,
      DmpClient dmp) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.json = json;
    this.clock = clock;
    this.audit = audit;
    this.bootstrap = bootstrap;
    this.settings = settings;
    this.mealUnits = mealUnits;
    this.forecast = forecast;
    this.dmp = dmp;
  }

  public record Counts(int created, int updated, int deactivated, int total) {}

  public record EmployeeCounts(int created, int updated, int left, int total) {}

  public record Skipped(String employeeCode, String orgUnitCode) {}

  /**
   * @param skippedEmployees DMP employees whose org node is unknown; they were not written
   * @param missingFromDmp employees in the database that DMP no longer returns (left unchanged,
   *     open question Q13)
   * @param mealsCancelledForLeavers BR-36: registrations cancelled because their day is on or after
   *     the employee's end date
   */
  public record Details(
      Counts orgUnits,
      EmployeeCounts employees,
      int costCentersCreated,
      int mealUnitsRecomputed,
      List<Skipped> skippedEmployees,
      int missingFromDmp,
      int mealsCancelledForLeavers) {}

  public record Result(String syncRunId, String status, Details details, String error) {}

  /**
   * @param actorId null for the scheduled job; a manual trigger is audited
   */
  public Result run(String factoryId, String actorId) {
    Instant now = clock.now();
    jdbc.sql(
            """
            SELECT "startedAt" FROM "SyncRun" WHERE type = 'ORG_EMPLOYEES' AND status = 'RUNNING'
            ORDER BY "startedAt" DESC LIMIT 1
            """)
        .query((rs, i) -> SqlTime.instant(rs, "startedAt"))
        .optional()
        .filter(startedAt -> Duration.between(startedAt, now).compareTo(STALE_RUN) < 0)
        .ifPresent(
            running -> {
              throw ApiException.invalidState("An org sync is already running", null);
            });

    String runId = Ids.newId(now.toEpochMilli());
    jdbc.sql(
            """
            INSERT INTO "SyncRun" (id, type, "startedAt", status)
            VALUES (:id, 'ORG_EMPLOYEES', :now, 'RUNNING')
            """)
        .param("id", runId)
        .param("now", SqlTime.utc(now))
        .update();
    if (actorId != null) {
      audit.log(AuditService.Entry.of(actorId, "sync.orgEmployees.triggered", "SyncRun", runId));
    }

    try {
      List<DmpClient.OrgNode> nodes = dmp.getOrgTree();
      List<DmpClient.Employee> employees = dmp.getEmployees();
      Details details =
          tx.execute(
              status -> {
                Boolean locked =
                    jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
                        .param("key", SYNC_LOCK_KEY)
                        .query(Boolean.class)
                        .single();
                if (!Boolean.TRUE.equals(locked)) {
                  throw new IllegalStateException("Another org sync holds the lock");
                }
                return write(factoryId, nodes, employees);
              });
      jdbc.sql(
              """
              UPDATE "SyncRun" SET status = 'SUCCESS', "finishedAt" = :now,
                     "recordCount" = :count, details = CAST(:details AS jsonb)
              WHERE id = :id
              """)
          .param("now", SqlTime.utc(clock.now()))
          .param("count", details.orgUnits().total() + details.employees().total())
          .param("details", json.writeValueAsString(details))
          .param("id", runId)
          .update();
      // Plan section 3: the first system admin may only exist after the first employee sync.
      bootstrap.ensure();
      return new Result(runId, "SUCCESS", details, null);
    } catch (RuntimeException e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      log.error("Org sync {} failed: {}", runId, message);
      jdbc.sql(
              """
              UPDATE "SyncRun" SET status = 'FAILED', "finishedAt" = :now, error = :error
              WHERE id = :id
              """)
          .param("now", SqlTime.utc(clock.now()))
          .param("error", message.length() > 2000 ? message.substring(0, 2000) : message)
          .param("id", runId)
          .update();
      return new Result(runId, "FAILED", null, message);
    }
  }

  /** Same as {@link #run} but answers 502 on failure, for the manual trigger endpoint. */
  public Result runOrThrow(String factoryId, String actorId) {
    Result result = run(factoryId, actorId);
    if (result.status().equals("FAILED")) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("syncRunId", result.syncRunId());
      details.put("error", result.error());
      throw new ApiException(
          HttpStatus.BAD_GATEWAY, ApiErrorCode.DMP_UNAVAILABLE, "Org sync failed", details, null);
    }
    return result;
  }

  private record UnitRow(
      String id,
      String dmpCode,
      String name,
      int level,
      String dmpLevelName,
      String parentId,
      String path,
      String dmpCostCenterCode,
      boolean active) {}

  private record Placed(String id, String path) {}

  private record EmployeeRow(
      String id,
      String employeeCode,
      String name,
      String email,
      String orgUnitId,
      String dmpCostCenterCode,
      String status,
      LocalDate startDate,
      LocalDate endDate) {}

  private Details write(
      String factoryId, List<DmpClient.OrgNode> dmpNodes, List<DmpClient.Employee> dmpEmployees) {
    // BR-61: validate the tree and compute levels 1..6 before writing anything.
    DmpTree.Result tree =
        DmpTree.build(
            dmpNodes.stream()
                .map(
                    n ->
                        new DmpTree.Input(
                            n.code(), n.name(), n.parentCode(), n.levelName(), n.costCenterCode()))
                .toList());
    if (!tree.ok()) {
      throw new IllegalStateException(
          "DMP org tree rejected: " + json.writeValueAsString(treeErrors(tree.errors())));
    }
    Instant now = clock.now();
    LocalDate today = VnTime.vnDateOf(now);

    // --- Org units ---------------------------------------------------------------------------
    List<UnitRow> existing =
        jdbc.sql(
                """
                SELECT id, "dmpCode", name, level, "dmpLevelName", "parentId", path,
                       "dmpCostCenterCode", active
                FROM "OrgUnit" WHERE "factoryId" = :factoryId
                """)
            .param("factoryId", factoryId)
            .query(UnitRow.class)
            .list();
    Map<String, UnitRow> byCode = new HashMap<>();
    existing.forEach(u -> byCode.put(u.dmpCode(), u));
    Map<String, Placed> placed = new HashMap<>();
    int created = 0;
    int updated = 0;
    for (DmpTree.Node node : tree.nodes()) {
      Placed parent = node.parentCode() == null ? null : placed.get(node.parentCode());
      UnitRow current = byCode.get(node.code());
      String id = current != null ? current.id() : "ou_" + UUID.randomUUID();
      String path = (parent == null ? "/" : parent.path()) + id + "/";
      String parentId = parent == null ? null : parent.id();
      placed.put(node.code(), new Placed(id, path));
      if (current == null) {
        jdbc.sql(
                """
                INSERT INTO "OrgUnit" (id, "factoryId", "dmpCode", name, level, "dmpLevelName",
                    "parentId", path, "dmpCostCenterCode", active, "createdAt")
                VALUES (:id, :factoryId, :code, :name, :level, :levelName, :parentId, :path,
                    :cc, true, :now)
                """)
            .param("id", id)
            .param("factoryId", factoryId)
            .param("code", node.code())
            .param("name", node.name())
            .param("level", node.level())
            .param("levelName", node.levelName())
            .param("parentId", parentId)
            .param("path", path)
            .param("cc", node.costCenterCode())
            .param("now", SqlTime.utc(now))
            .update();
        created++;
      } else if (!current.name().equals(node.name())
          || current.level() != node.level()
          || !current.dmpLevelName().equals(node.levelName())
          || !Objects.equals(current.parentId(), parentId)
          || !current.path().equals(path)
          || !Objects.equals(current.dmpCostCenterCode(), node.costCenterCode())
          || !current.active()) {
        jdbc.sql(
                """
                UPDATE "OrgUnit" SET name = :name, level = :level, "dmpLevelName" = :levelName,
                    "parentId" = :parentId, path = :path, "dmpCostCenterCode" = :cc, active = true
                WHERE id = :id
                """)
            .param("name", node.name())
            .param("level", node.level())
            .param("levelName", node.levelName())
            .param("parentId", parentId)
            .param("path", path)
            .param("cc", node.costCenterCode())
            .param("id", id)
            .update();
        updated++;
      }
    }
    Set<String> seen = new HashSet<>();
    tree.nodes().forEach(n -> seen.add(n.code()));
    List<String> gone =
        existing.stream()
            .filter(u -> u.active() && !seen.contains(u.dmpCode()))
            .map(UnitRow::id)
            .toList();
    int deactivated =
        gone.isEmpty()
            ? 0
            : jdbc.sql("UPDATE \"OrgUnit\" SET active = false WHERE id IN (:ids)")
                .param("ids", gone)
                .update();
    Counts orgUnits = new Counts(created, updated, deactivated, tree.nodes().size());

    // --- Cost centers supplied by DMP (BR-62: DMP is the default, admin mapping overrides) ------
    Set<String> ccCodes = new LinkedHashSet<>();
    dmpEmployees.forEach(e -> addCode(ccCodes, e.costCenterCode()));
    dmpNodes.forEach(n -> addCode(ccCodes, n.costCenterCode()));
    Set<String> knownCc =
        new HashSet<>(
            jdbc.sql("SELECT code FROM \"CostCenter\" WHERE \"factoryId\" = :factoryId")
                .param("factoryId", factoryId)
                .query(String.class)
                .list());
    List<String> newCc = ccCodes.stream().filter(c -> !knownCc.contains(c)).toList();
    for (String code : newCc) {
      jdbc.sql(
              """
              INSERT INTO "CostCenter" (id, "factoryId", code, name, source)
              VALUES (:id, :factoryId, :code, :code, 'DMP') ON CONFLICT DO NOTHING
              """)
          .param("id", Ids.newId(now.toEpochMilli()))
          .param("factoryId", factoryId)
          .param("code", code)
          .update();
    }

    // --- Employees ---------------------------------------------------------------------------
    List<EmployeeRow> current =
        jdbc.sql(
                """
                SELECT e.id, e."employeeCode", e.name, e.email, e."orgUnitId",
                       e."dmpCostCenterCode", e.status::text, e."startDate", e."endDate"
                FROM "Employee" e JOIN "OrgUnit" o ON o.id = e."orgUnitId"
                WHERE o."factoryId" = :factoryId
                """)
            .param("factoryId", factoryId)
            .query(EmployeeRow.class)
            .list();
    Map<String, EmployeeRow> empByCode = new HashMap<>();
    current.forEach(e -> empByCode.put(e.employeeCode(), e));
    int empCreated = 0;
    int empUpdated = 0;
    int left = 0;
    int total = 0;
    List<Skipped> skipped = new ArrayList<>();
    Set<String> seenCodes = new HashSet<>();

    for (DmpClient.Employee e : dmpEmployees) {
      String code = e.employeeCode().strip();
      if (code.isEmpty() || !seenCodes.add(code)) {
        continue;
      }
      Placed unit = placed.get(e.orgUnitCode());
      if (unit == null) {
        skipped.add(new Skipped(code, e.orgUnitCode()));
        continue;
      }
      total++;
      String status = e.endDate() != null && !e.endDate().isAfter(today) ? "LEFT" : "ACTIVE";
      String email = blankToNull(e.email());
      String cc = blankToNull(e.costCenterCode());
      EmployeeRow prev = empByCode.get(code);
      if (prev == null) {
        // No PIN yet: a new employee signs in with Microsoft or gets a PIN from an admin.
        jdbc.sql(
                """
                INSERT INTO "Employee" (id, "employeeCode", name, email, "orgUnitId",
                    "dmpCostCenterCode", status, "startDate", "endDate", "pinMustChange",
                    "createdAt")
                VALUES (:id, :code, :name, :email, :orgUnitId, :cc,
                    CAST(:status AS "EmployeeStatus"), :startDate, :endDate, true, :now)
                """)
            .param("id", Ids.newId(now.toEpochMilli()))
            .param("code", code)
            .param("name", e.name())
            .param("email", email)
            .param("orgUnitId", unit.id())
            .param("cc", cc)
            .param("status", status)
            .param("startDate", e.startDate())
            .param("endDate", e.endDate())
            .param("now", SqlTime.utc(now))
            .update();
        empCreated++;
        if (status.equals("LEFT")) {
          left++;
        }
        continue;
      }
      boolean changed =
          !prev.name().equals(e.name())
              || !Objects.equals(prev.email(), email)
              || !prev.orgUnitId().equals(unit.id())
              || !Objects.equals(prev.dmpCostCenterCode(), cc)
              || !prev.status().equals(status)
              || !Objects.equals(prev.startDate(), e.startDate())
              || !Objects.equals(prev.endDate(), e.endDate());
      if (changed) {
        jdbc.sql(
                """
                UPDATE "Employee" SET name = :name, email = :email, "orgUnitId" = :orgUnitId,
                    "dmpCostCenterCode" = :cc, status = CAST(:status AS "EmployeeStatus"),
                    "startDate" = :startDate, "endDate" = :endDate
                WHERE id = :id
                """)
            .param("name", e.name())
            .param("email", email)
            .param("orgUnitId", unit.id())
            .param("cc", cc)
            .param("status", status)
            .param("startDate", e.startDate())
            .param("endDate", e.endDate())
            .param("id", prev.id())
            .update();
        empUpdated++;
        if (prev.status().equals("ACTIVE") && status.equals("LEFT")) {
          left++;
        }
      }
    }

    // BR-60: meal units follow the (possibly moved) tree.
    int recomputed = mealUnits.recompute(factoryId);
    // BR-36: leavers lose their meals from their end date.
    int leaversMeals =
        forecast.cancelLeaversMeals(
            factoryId, now, settings.getTime(factoryId, SettingKey.DAY_LOCK_TIME));

    return new Details(
        orgUnits,
        new EmployeeCounts(empCreated, empUpdated, left, total),
        newCc.size(),
        recomputed,
        List.copyOf(skipped.subList(0, Math.min(200, skipped.size()))),
        // TODO(BR-36): what an employee missing from DMP means is open (open-questions Q13).
        (int) current.stream().filter(e -> !seenCodes.contains(e.employeeCode())).count(),
        leaversMeals);
  }

  private static void addCode(Set<String> codes, String code) {
    if (code != null && !code.isEmpty()) {
      codes.add(code.strip());
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.strip().isEmpty() ? null : value.strip();
  }

  /** The first ten tree errors in the legacy JSON shape (fields that do not apply left out). */
  private static List<Map<String, Object>> treeErrors(List<DmpTree.Error> errors) {
    return errors.stream()
        .limit(10)
        .map(
            e -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("code", e.code().name());
              m.put("nodeCode", e.nodeCode());
              if (e.parentCode() != null) {
                m.put("parentCode", e.parentCode());
              }
              if (e.depth() != null) {
                m.put("depth", e.depth());
              }
              return m;
            })
        .toList();
  }
}
