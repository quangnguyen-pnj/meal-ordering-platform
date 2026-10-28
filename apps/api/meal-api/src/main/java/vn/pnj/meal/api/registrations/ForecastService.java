package vn.pnj.meal.api.registrations;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.common.Times;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.db.SqlTime;
import vn.pnj.meal.api.org.OrgDirectory;
import vn.pnj.meal.api.org.OrgSnapshot;
import vn.pnj.meal.api.settings.SettingKey;
import vn.pnj.meal.api.settings.SettingsService;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.deadline.Deadlines;
import vn.pnj.meal.rules.org.Mapping;
import vn.pnj.meal.rules.org.Mapping.EmployeeMealAssignment;
import vn.pnj.meal.rules.registration.RegistrationRules;
import vn.pnj.meal.rules.registration.RegistrationRules.Status;

/**
 * Checkpoints of registration that happen at an instant rather than on a request:
 *
 * <ul>
 *   <li>BR-07: the forecast snapshot at each week's closeAt (close-week job).
 *   <li>BR-14: registrations still waiting for reselection are cancelled at the day's lock.
 *   <li>BR-36: leavers lose their meals from their end date (called by the org sync).
 * </ul>
 *
 * Every method is idempotent. Jobs call them on a schedule, and registration requests (J3) call
 * {@link #catchUp} first, so a missed job never lets a rule slip (plan 6.5: self-healing).
 */
@Service
public class ForecastService {
  private static final Logger log = LoggerFactory.getLogger(ForecastService.class);

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batch;
  private final TransactionTemplate tx;
  private final ClockService clock;
  private final SettingsService settings;
  private final AuditService audit;
  private final OrgDirectory org;

  public ForecastService(
      JdbcClient jdbc,
      NamedParameterJdbcTemplate batch,
      TransactionTemplate tx,
      ClockService clock,
      SettingsService settings,
      AuditService audit,
      OrgDirectory org) {
    this.jdbc = jdbc;
    this.batch = batch;
    this.tx = tx;
    this.clock = clock;
    this.settings = settings;
    this.audit = audit;
    this.org = org;
  }

  /** Everything that is due for the factory: snapshots, then lock-time cancellations. */
  public void catchUp(String factoryId) {
    closeDueWeeks(factoryId);
    cancelUnselectedAtLock(factoryId);
  }

  /** BR-07: takes the forecast snapshot of every published week whose closeAt has passed. */
  public int closeDueWeeks(String factoryId) {
    List<String> due =
        jdbc.sql(
                """
                SELECT id FROM "MenuWeek"
                WHERE status = 'PUBLISHED' AND "closedAt" IS NULL AND "closeAt" <= :now
                  AND (CAST(:factoryId AS text) IS NULL OR "factoryId" = :factoryId)
                """)
            .param("now", SqlTime.utc(clock.now()))
            .param("factoryId", factoryId)
            .query(String.class)
            .list();
    int closed = 0;
    for (String weekId : due) {
      if (ensureClosed(weekId)) {
        closed++;
      }
    }
    return closed;
  }

  private record Week(String factoryId, String canteenId, LocalDate weekStart, Instant closeAt) {}

  private record Line(
      String employeeId,
      String menuItemId,
      LocalDate date,
      String session,
      int quantity,
      String status) {}

  private record EmployeeRef(String id, String orgUnitId, String floorOverrideId, String code) {}

  /**
   * BR-07 / BR-64: writes the immutable ForecastLine snapshot of one week with the mapping in
   * effect now, and marks the week CLOSED. The conditional update takes the week's row lock, so it
   * waits for registration writes holding FOR SHARE, and two runs can never both write lines.
   * Returns true when this call took the snapshot.
   */
  public boolean ensureClosed(String menuWeekId) {
    Instant now = clock.now();
    Boolean taken =
        tx.execute(
            status -> {
              int updated =
                  jdbc.sql(
                          """
                          UPDATE "MenuWeek" SET status = 'CLOSED', "closedAt" = :now,
                                 "updatedAt" = :now
                          WHERE id = :id AND status = 'PUBLISHED' AND "closedAt" IS NULL
                            AND "closeAt" <= :now
                          """)
                      .param("now", SqlTime.utc(now))
                      .param("id", menuWeekId)
                      .update();
              if (updated == 0) {
                return false;
              }
              Week week =
                  jdbc.sql(
                          """
                          SELECT "factoryId", "canteenId", "weekStart", "closeAt"
                          FROM "MenuWeek" WHERE id = :id
                          """)
                      .param("id", menuWeekId)
                      .query(
                          (rs, i) ->
                              new Week(
                                  rs.getString("factoryId"),
                                  rs.getString("canteenId"),
                                  rs.getObject("weekStart", LocalDate.class),
                                  SqlTime.instant(rs, "closeAt")))
                      .single();
              List<Line> lines =
                  jdbc
                      .sql(
                          """
                          SELECT r."employeeId", r."menuItemId", r.date, r.session::text,
                                 r.quantity, r.status::text
                          FROM "Registration" r
                          JOIN "MenuItem" i ON i.id = r."menuItemId"
                          JOIN "MenuDay" d ON d.id = i."menuDayId"
                          WHERE d."menuWeekId" = :id
                          """)
                      .param("id", menuWeekId)
                      .query(Line.class)
                      .list()
                      .stream()
                      .filter(
                          l ->
                              RegistrationRules.isForecastLine(
                                  Status.valueOf(l.status()), l.quantity()))
                      .toList();
              writeLines(menuWeekId, week, lines, now);
              Map<String, Object> after = new LinkedHashMap<>();
              after.put("weekStart", week.weekStart().toString());
              after.put("canteenId", week.canteenId());
              after.put("closeAt", Times.iso(week.closeAt()));
              after.put("lines", lines.size());
              after.put("meals", lines.stream().mapToInt(Line::quantity).sum());
              audit.log(
                  AuditService.Entry.of(null, "forecast.snapshot", "MenuWeek", menuWeekId)
                      .withAfter(after));
              log.info("Forecast snapshot of week {}: {} lines", menuWeekId, lines.size());
              return true;
            });
    return Boolean.TRUE.equals(taken);
  }

  private void writeLines(String menuWeekId, Week week, List<Line> lines, Instant now) {
    if (lines.isEmpty()) {
      return;
    }
    List<String> employeeIds =
        List.copyOf(new LinkedHashSet<>(lines.stream().map(Line::employeeId).toList()));
    OrgSnapshot snapshot = org.load(week.factoryId());
    Map<String, String> ccByCode = org.costCenterIdsByCode(week.factoryId());
    Map<String, EmployeeMealAssignment> mapping = new HashMap<>();
    jdbc.sql(
            """
            SELECT id, "orgUnitId", "floorOverrideId", "dmpCostCenterCode" AS code
            FROM "Employee" WHERE id IN (:ids)
            """)
        .param("ids", employeeIds)
        .query(EmployeeRef.class)
        .list()
        .forEach(
            e ->
                mapping.put(
                    e.id(),
                    Mapping.resolveEmployeeMealAssignment(
                        e.orgUnitId(),
                        e.floorOverrideId(),
                        e.code() == null ? null : ccByCode.get(e.code()),
                        snapshot.index())));
    MapSqlParameterSource[] rows =
        lines.stream()
            .map(
                l -> {
                  EmployeeMealAssignment a = mapping.get(l.employeeId());
                  return new MapSqlParameterSource()
                      .addValue("id", Ids.newId(now.toEpochMilli()))
                      .addValue("menuWeekId", menuWeekId)
                      .addValue("employeeId", l.employeeId())
                      .addValue("date", l.date())
                      .addValue("session", l.session())
                      .addValue("menuItemId", l.menuItemId())
                      .addValue("quantity", l.quantity())
                      .addValue("mealUnitId", a.mealUnitId())
                      .addValue("floorId", a.floorId())
                      // The menu's canteen is the one the meals were taken against.
                      .addValue("canteenId", week.canteenId())
                      .addValue("costCenterId", a.costCenterId())
                      .addValue("now", SqlTime.utc(now));
                })
            .toArray(MapSqlParameterSource[]::new);
    batch.batchUpdate(
        """
        INSERT INTO "ForecastLine" (id, "menuWeekId", "employeeId", date, session, "menuItemId",
            quantity, "mealUnitId", "floorId", "canteenId", "costCenterId", "createdAt")
        VALUES (:id, :menuWeekId, :employeeId, :date, CAST(:session AS "MealSession"),
            :menuItemId, :quantity, :mealUnitId, :floorId, :canteenId, :costCenterId, :now)
        """,
        rows);
  }

  private record Pending(
      String id, String employeeId, String menuItemId, LocalDate date, int quantity) {}

  /**
   * BR-14: a registration on a removed dish that is still waiting for reselection at the day's lock
   * is cancelled. Automatic, so audited per registration with no actor (BR-70, Q18).
   */
  public int cancelUnselectedAtLock(String factoryId) {
    Instant now = clock.now();
    LocalDate today = VnTime.vnDateOf(now);
    List<String> factories =
        factoryId != null
            ? List.of(factoryId)
            : jdbc.sql("SELECT id FROM \"Factory\"").query(String.class).list();
    int cancelled = 0;
    for (String fid : factories) {
      LocalTime lockTime = settings.getTime(fid, SettingKey.DAY_LOCK_TIME);
      boolean locked = !now.isBefore(Deadlines.dayLockAt(today, lockTime));
      List<Pending> rows =
          jdbc.sql(
                  """
                  SELECT r.id, r."employeeId", r."menuItemId", r.date, r.quantity
                  FROM "Registration" r
                  JOIN "Employee" e ON e.id = r."employeeId"
                  JOIN "OrgUnit" o ON o.id = e."orgUnitId"
                  WHERE r.status = 'NEEDS_RESELECT' AND o."factoryId" = :factoryId
                    AND (r.date < :today OR (:locked AND r.date = :today))
                  """)
              .param("factoryId", fid)
              .param("today", today)
              .param("locked", locked)
              .query(Pending.class)
              .list();
      if (rows.isEmpty()) {
        continue;
      }
      Integer done =
          tx.execute(
              status -> {
                int count = 0;
                for (Pending r : rows) {
                  int updated =
                      jdbc.sql(
                              """
                              UPDATE "Registration" SET status = 'CANCELLED', "updatedAt" = :now
                              WHERE id = :id AND status = 'NEEDS_RESELECT'
                              """)
                          .param("now", SqlTime.utc(now))
                          .param("id", r.id())
                          .update();
                  if (updated == 0) {
                    continue;
                  }
                  count++;
                  Map<String, Object> before = new LinkedHashMap<>();
                  before.put("status", "NEEDS_RESELECT");
                  before.put("quantity", r.quantity());
                  Map<String, Object> after = new LinkedHashMap<>();
                  after.put("status", "CANCELLED");
                  after.put("date", r.date().toString());
                  after.put("menuItemId", r.menuItemId());
                  audit.log(
                      AuditService.Entry.of(
                              null, "registration.cancelledUnselected", "Registration", r.id())
                          .withBefore(before)
                          .withAfter(after));
                }
                return count;
              });
      cancelled += done == null ? 0 : done;
    }
    return cancelled;
  }

  private record LeaverMeal(
      String id,
      LocalDate date,
      int quantity,
      String status,
      String menuItemId,
      LocalDate endDate) {}

  /**
   * BR-36: employees who leave lose all meals from their end date. Runs inside the org sync's
   * transaction; days whose lock has passed are left to reconciliation. Each cancellation is
   * audited with no actor (BR-70).
   */
  public int cancelLeaversMeals(String factoryId, Instant now, LocalTime lockTime) {
    LocalDate today = VnTime.vnDateOf(now);
    List<LeaverMeal> rows =
        jdbc.sql(
                """
                SELECT r.id, r.date, r.quantity, r.status::text, r."menuItemId", e."endDate"
                FROM "Registration" r
                JOIN "Employee" e ON e.id = r."employeeId"
                JOIN "OrgUnit" o ON o.id = e."orgUnitId"
                WHERE r.status IN ('ACTIVE', 'NEEDS_RESELECT') AND r.date >= :today
                  AND e."endDate" IS NOT NULL AND o."factoryId" = :factoryId
                """)
            .param("today", today)
            .param("factoryId", factoryId)
            .query(LeaverMeal.class)
            .list();
    int cancelled = 0;
    for (LeaverMeal r : rows) {
      if (!RegistrationRules.isLostByLeaver(r.date(), r.endDate(), now, lockTime)) {
        continue;
      }
      jdbc.sql(
              "UPDATE \"Registration\" SET status = 'CANCELLED', \"updatedAt\" = :now WHERE id = :id")
          .param("now", SqlTime.utc(now))
          .param("id", r.id())
          .update();
      Map<String, Object> before = new LinkedHashMap<>();
      before.put("status", r.status());
      before.put("quantity", r.quantity());
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("status", "CANCELLED");
      after.put("date", r.date().toString());
      after.put("menuItemId", r.menuItemId());
      after.put("endDate", r.endDate().toString());
      audit.log(
          AuditService.Entry.of(null, "registration.cancelledLeaver", "Registration", r.id())
              .withBefore(before)
              .withAfter(after));
      cancelled++;
    }
    return cancelled;
  }
}
