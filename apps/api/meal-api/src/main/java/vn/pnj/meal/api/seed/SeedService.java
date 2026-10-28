package vn.pnj.meal.api.seed;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.pnj.meal.api.auth.PinHasher;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.config.AppProperties;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.db.SqlTime;
import vn.pnj.meal.api.settings.SettingKey;
import vn.pnj.meal.rules.auth.PinRules;
import vn.pnj.meal.rules.org.Mapping;
import vn.pnj.meal.rules.org.OrgIndex;

/**
 * Development seed (plan P0): 1 factory, 1 canteen, 4 floors, 10 cost centers, a 6-level org tree
 * with partial mapping, 1,700 fake employees and all 4 roles: the same rows as the legacy seed.
 * Idempotent: re-running adds only what is missing. Refuses to run in production.
 */
@Service
public class SeedService {
  private static final Logger log = LoggerFactory.getLogger(SeedService.class);

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate named;
  private final PinHasher pins;
  private final ClockService clock;
  private final AppProperties app;
  private final SeedProperties seed;

  public SeedService(
      JdbcClient jdbc,
      NamedParameterJdbcTemplate named,
      PinHasher pins,
      ClockService clock,
      AppProperties app,
      SeedProperties seed) {
    this.jdbc = jdbc;
    this.named = named;
    this.pins = pins;
    this.clock = clock;
    this.app = app;
    this.seed = seed;
  }

  @Transactional
  public void run() {
    if (app.environment() == AppProperties.Environment.PRODUCTION) {
      throw new IllegalStateException("Refusing to seed fake data in production");
    }
    String initialPin = seed.initialPin() == null ? "123456" : seed.initialPin();
    if (!PinRules.isWellFormed(initialPin)) {
      throw new IllegalStateException("SEED_INITIAL_PIN must be exactly 6 digits");
    }
    String firstSysAdmin =
        app.firstSystemAdminCode() != null
            ? app.firstSystemAdminCode()
            : SeedEmployees.Demo.SYS_ADMIN;
    Instant now = clock.now();
    String factoryId = SeedOrgTree.FACTORY.id();

    var f = SeedOrgTree.FACTORY;
    jdbc.sql(
            """
            INSERT INTO "Factory" (id, code, name, "dmpCompanyCode", timezone)
            VALUES (:id, :code, :name, :dmp, :tz) ON CONFLICT (id) DO NOTHING
            """)
        .param("id", f.id())
        .param("code", f.code())
        .param("name", f.name())
        .param("dmp", f.dmpCompanyCode())
        .param("tz", f.timezone())
        .update();

    for (SettingKey key : SettingKey.values()) {
      jdbc.sql(
              """
              INSERT INTO "Setting" (id, "factoryId", key, value, "updatedAt")
              VALUES (:id, :factoryId, :key, CAST(:value AS jsonb), :now)
              ON CONFLICT ("factoryId", key) DO NOTHING
              """)
          .param("id", Ids.newId(now.toEpochMilli()))
          .param("factoryId", factoryId)
          .param("key", key.code())
          .param("value", key.defaultJson())
          .param("now", SqlTime.utc(now))
          .update();
    }

    var s = SeedOrgTree.SUPPLIER;
    jdbc.sql(
            """
            INSERT INTO "Supplier" (id, "factoryId", name, "unitPrice")
            VALUES (:id, :factoryId, :name, :price) ON CONFLICT (id) DO NOTHING
            """)
        .param("id", s.id())
        .param("factoryId", factoryId)
        .param("name", s.name())
        .param("price", new BigDecimal(s.unitPrice()))
        .update();
    var c = SeedOrgTree.CANTEEN;
    jdbc.sql(
            """
            INSERT INTO "Canteen" (id, "factoryId", code, name, "supplierId")
            VALUES (:id, :factoryId, :code, :name, :supplierId) ON CONFLICT (id) DO NOTHING
            """)
        .param("id", c.id())
        .param("factoryId", factoryId)
        .param("code", c.code())
        .param("name", c.name())
        .param("supplierId", s.id())
        .update();
    for (var t : SeedOrgTree.DISH_TYPES) {
      jdbc.sql(
              """
              INSERT INTO "DishType"
                (id, "factoryId", code, name, color, "sortOrder", "minPerDay", "maxPerDay")
              VALUES (:id, :factoryId, :code, :name, :color, :sortOrder, :min, :max)
              ON CONFLICT DO NOTHING
              """)
          .param("id", SeedOrgTree.dishTypeId(t.code()))
          .param("factoryId", factoryId)
          .param("code", t.code())
          .param("name", t.name())
          .param("color", t.color())
          .param("sortOrder", t.sortOrder())
          .param("min", t.minPerDay())
          .param("max", t.maxPerDay())
          .update();
    }
    for (var fl : SeedOrgTree.FLOORS) {
      jdbc.sql(
              """
              INSERT INTO "Floor" (id, "factoryId", code, name, "sortOrder")
              VALUES (:id, :factoryId, :code, :name, :sortOrder) ON CONFLICT DO NOTHING
              """)
          .param("id", fl.id())
          .param("factoryId", factoryId)
          .param("code", fl.code())
          .param("name", fl.name())
          .param("sortOrder", fl.sortOrder())
          .update();
    }
    for (var cc : SeedOrgTree.COST_CENTERS) {
      jdbc.sql(
              """
              INSERT INTO "CostCenter" (id, "factoryId", code, name, source)
              VALUES (:id, :factoryId, :code, :name, CAST(:source AS "CostCenterSource"))
              ON CONFLICT DO NOTHING
              """)
          .param("id", cc.id())
          .param("factoryId", factoryId)
          .param("code", cc.code())
          .param("name", cc.name())
          .param("source", cc.source())
          .update();
    }

    List<SeedOrgTree.Node> nodes = SeedOrgTree.build();
    MapSqlParameterSource[] nodeRows =
        nodes.stream()
            .map(
                n ->
                    new MapSqlParameterSource()
                        .addValue("id", n.id())
                        .addValue("factoryId", n.factoryId())
                        .addValue("dmpCode", n.dmpCode())
                        .addValue("name", n.name())
                        .addValue("level", n.level())
                        .addValue("dmpLevelName", n.dmpLevelName())
                        .addValue("parentId", n.parentId())
                        .addValue("path", n.path())
                        .addValue("isMealUnit", n.isMealUnit())
                        .addValue("floorId", n.floorId())
                        .addValue("canteenId", n.canteenId())
                        .addValue("costCenterId", n.costCenterId()))
            .toArray(MapSqlParameterSource[]::new);
    named.batchUpdate(
        """
        INSERT INTO "OrgUnit" (id, "factoryId", "dmpCode", name, level, "dmpLevelName", "parentId",
                               path, "isMealUnit", "floorId", "canteenId", "costCenterId")
        VALUES (:id, :factoryId, :dmpCode, :name, :level, :dmpLevelName, :parentId,
                :path, :isMealUnit, :floorId, :canteenId, :costCenterId)
        ON CONFLICT DO NOTHING
        """,
        nodeRows);

    // BR-60: the meal unit is the nearest flagged node at or above the employee's own node.
    OrgIndex index = OrgIndex.of(nodes.stream().map(SeedOrgTree.Node::toRuleNode).toList());
    // One hash for everyone: hashing 1,700 PINs would take minutes; every seeded employee starts
    // with the same temporary PIN and must change it at first sign-in.
    String pinHash = pins.hash(initialPin);
    MapSqlParameterSource[] employeeRows =
        SeedEmployees.build(nodes).stream()
            .map(
                p ->
                    new MapSqlParameterSource()
                        .addValue("id", Ids.newId(now.toEpochMilli()))
                        .addValue("code", p.employeeCode())
                        .addValue("name", p.name())
                        .addValue("email", p.email())
                        .addValue("orgUnitId", p.orgUnitId())
                        .addValue("mealUnitId", Mapping.resolveMealUnitId(p.orgUnitId(), index))
                        .addValue("dmpCc", p.dmpCostCenterCode())
                        .addValue("startDate", LocalDate.parse(p.startDate()))
                        .addValue("pinHash", pinHash))
            .toArray(MapSqlParameterSource[]::new);
    named.batchUpdate(
        """
        INSERT INTO "Employee" (id, "employeeCode", name, email, "orgUnitId", "mealUnitId",
                                "dmpCostCenterCode", "startDate", "pinHash", "pinMustChange")
        VALUES (:id, :code, :name, :email, :orgUnitId, :mealUnitId,
                :dmpCc, :startDate, :pinHash, true)
        ON CONFLICT ("employeeCode") DO NOTHING
        """,
        employeeRows);

    Map<String, String> idByCode = new HashMap<>();
    jdbc.sql("SELECT id, \"employeeCode\" FROM \"Employee\"")
        .query((rs, i) -> idByCode.put(rs.getString(2), rs.getString(1)))
        .list();
    List<SeedEmployees.RoleDef> roles =
        new java.util.ArrayList<>(
            SeedEmployees.ROLE_ASSIGNMENTS.stream()
                .filter(r -> !"SYS_ADMIN".equals(r.role()))
                .toList());
    roles.add(new SeedEmployees.RoleDef(firstSysAdmin, "SYS_ADMIN", null));
    for (SeedEmployees.RoleDef r : roles) {
      String employeeId = idByCode.get(r.employeeCode());
      if (employeeId == null) {
        throw new IllegalStateException(
            "Role assignment refers to unknown employee " + r.employeeCode());
      }
      String orgUnitId = r.orgUnitCode() == null ? null : SeedOrgTree.orgUnitId(r.orgUnitCode());
      boolean exists =
          jdbc.sql(
                      """
                  SELECT count(*) FROM "RoleAssignment"
                  WHERE "employeeId" = :employeeId AND role = CAST(:role AS "AssignableRole")
                    AND "orgUnitId" IS NOT DISTINCT FROM CAST(:orgUnitId AS text)
                  """)
                  .param("employeeId", employeeId)
                  .param("role", r.role())
                  .param("orgUnitId", orgUnitId)
                  .query(Long.class)
                  .single()
              > 0;
      if (!exists) {
        jdbc.sql(
                """
                INSERT INTO "RoleAssignment" (id, "employeeId", role, "factoryId", "orgUnitId")
                VALUES (:id, :employeeId, CAST(:role AS "AssignableRole"), :factoryId, :orgUnitId)
                """)
            .param("id", Ids.newId(now.toEpochMilli()))
            .param("employeeId", employeeId)
            .param("role", r.role())
            .param("factoryId", factoryId)
            .param("orgUnitId", orgUnitId)
            .update();
      }
    }

    log.info(
        "Seed complete: {} org units, {} employees, {} role assignments",
        count("OrgUnit"),
        count("Employee"),
        count("RoleAssignment"));
    log.info(
        "Demo sign-ins (initial PIN from SEED_INITIAL_PIN, change required at first sign-in):"
            + " employee {}, dept admin {}, HC admin {}, system admin {}",
        SeedEmployees.Demo.EMPLOYEE,
        SeedEmployees.Demo.DEPT_ADMIN_PRODUCTION,
        SeedEmployees.Demo.HC_ADMIN,
        firstSysAdmin);
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM \"" + table + "\"").query(Long.class).single();
  }
}
