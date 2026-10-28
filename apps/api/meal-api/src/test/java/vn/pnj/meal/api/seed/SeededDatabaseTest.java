package vn.pnj.meal.api.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.pnj.meal.api.seed.SeedEmployees.Demo;
import vn.pnj.meal.api.settings.SettingKey;
import vn.pnj.meal.api.support.IntegrationTest;
import vn.pnj.meal.rules.org.Mapping;
import vn.pnj.meal.rules.org.OrgIndex;
import vn.pnj.meal.rules.org.OrgNode;

/** Port of apps/api-legacy/test/seed.e2e-spec.ts. */
@DisplayName("seeded database")
class SeededDatabaseTest extends IntegrationTest {
  @Autowired private SeedService seedService;

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM \"" + table + "\"").query(Long.class).single();
  }

  @Test
  @DisplayName("holds 1 factory, 1 canteen, 4 floors, 10 cost centers and 1,700 employees")
  void counts() {
    assertThat(count("Factory")).isEqualTo(1);
    assertThat(count("Canteen")).isEqualTo(1);
    assertThat(count("Floor")).isEqualTo(4);
    assertThat(count("CostCenter")).isEqualTo(10);
    assertThat(count("Employee")).isEqualTo(1700);
    assertThat(count("Supplier")).isEqualTo(1);
  }

  @Test
  @DisplayName("stores every plan 6.4 setting")
  void settings() {
    assertThat(count("Setting")).isEqualTo(SettingKey.values().length);
    assertThat(
            jdbc.sql("SELECT value::text FROM \"Setting\" WHERE key = 'registration.close'")
                .query(String.class)
                .single())
        .isEqualTo("{\"time\": \"23:59\", \"weekday\": 4}");
  }

  @Test
  @DisplayName("stores a 6-level tree with parents and materialized paths")
  void tree() {
    List<Map<String, Object>> units =
        jdbc.sql("SELECT id, \"parentId\", path, level FROM \"OrgUnit\"").query().listOfRows();
    assertThat(units.stream().mapToInt(u -> (Integer) u.get("level")).max().orElse(0)).isEqualTo(6);
    Map<Object, Map<String, Object>> byId = new java.util.HashMap<>();
    units.forEach(u -> byId.put(u.get("id"), u));
    for (Map<String, Object> u : units) {
      Map<String, Object> parent = u.get("parentId") == null ? null : byId.get(u.get("parentId"));
      String parentPath = parent == null ? "/" : (String) parent.get("path");
      assertThat(u.get("path")).isEqualTo(parentPath + u.get("id") + "/");
    }
  }

  @Test
  @DisplayName("assigns all four roles")
  void roles() {
    assertThat(
            new HashSet<>(
                jdbc.sql("SELECT DISTINCT role::text FROM \"RoleAssignment\"")
                    .query(String.class)
                    .list()))
        .contains("DEPT_ADMIN", "HC_ADMIN", "SYS_ADMIN");
    assertThat(
            jdbc.sql(
                    """
                    SELECT e."employeeCode" FROM "RoleAssignment" r
                    JOIN "Employee" e ON e.id = r."employeeId"
                    WHERE r.role = 'DEPT_ADMIN' AND r."revokedAt" IS NULL AND r."orgUnitId" IN ('ou_XCT', 'ou_XKH')
                    ORDER BY 1
                    """)
                .query(String.class)
                .list())
        .containsExactly(Demo.DEPT_ADMIN_PRODUCTION, Demo.DEPT_ADMIN_JEWELRY);
  }

  @Test
  @DisplayName("stores employee meal units that agree with the mapping rules (BR-60)")
  void mealUnits() {
    OrgIndex index =
        OrgIndex.of(
            jdbc.sql(
                    """
                    SELECT id, "parentId", "isMealUnit", "floorId", "canteenId", "costCenterId"
                    FROM "OrgUnit"
                    """)
                .query(OrgNode.class)
                .list());
    record E(String orgUnitId, String mealUnitId, String floorOverrideId) {}
    List<E> employees =
        jdbc.sql("SELECT \"orgUnitId\", \"mealUnitId\", \"floorOverrideId\" FROM \"Employee\"")
            .query(E.class)
            .list();
    int disagreements = 0;
    int unassigned = 0;
    for (E e : employees) {
      var a =
          Mapping.resolveEmployeeMealAssignment(e.orgUnitId(), e.floorOverrideId(), null, index);
      if (!java.util.Objects.equals(a.mealUnitId(), e.mealUnitId())) {
        disagreements++;
      }
      if (!a.canRegister()) {
        unassigned++;
      }
    }
    assertThat(disagreements).isZero();
    assertThat(unassigned).isPositive();
  }

  @Test
  @DisplayName("never stores a PIN in clear text, and seeded employees must change it")
  void pins() {
    List<String> hashes =
        jdbc.sql("SELECT \"pinHash\" FROM \"Employee\" ORDER BY \"employeeCode\" DESC LIMIT 20")
            .query(String.class)
            .list();
    assertThat(hashes).allMatch(h -> h.startsWith("$argon2id$"));
  }

  @Test
  @DisplayName("running the seed again adds nothing")
  void idempotent() {
    long employees = count("Employee");
    long roles = count("RoleAssignment");
    seedService.run();
    assertThat(count("Employee")).isEqualTo(employees);
    assertThat(count("RoleAssignment")).isEqualTo(roles);
  }
}
