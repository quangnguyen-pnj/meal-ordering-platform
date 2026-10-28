package vn.pnj.meal.api.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.api.seed.SeedEmployees.Demo;
import vn.pnj.meal.rules.org.Mapping;
import vn.pnj.meal.rules.org.Mapping.CostCenterSource;
import vn.pnj.meal.rules.org.Mapping.EmployeeMealAssignment;
import vn.pnj.meal.rules.org.Mapping.Field;
import vn.pnj.meal.rules.org.Mapping.FloorSource;
import vn.pnj.meal.rules.org.Mapping.MealStatus;
import vn.pnj.meal.rules.org.OrgIndex;

/** Port of apps/api-legacy/src/seed-data/seed-data.spec.ts. */
class SeedDataTest {
  private static final List<SeedOrgTree.Node> NODES = SeedOrgTree.build();
  private static final OrgIndex INDEX =
      OrgIndex.of(NODES.stream().map(SeedOrgTree.Node::toRuleNode).toList());
  private static final List<SeedEmployees.Person> EMPLOYEES = SeedEmployees.build(NODES);

  private static String ou(String code) {
    return SeedOrgTree.orgUnitId(code);
  }

  private static EmployeeMealAssignment assignmentOf(String orgUnit, String floorOverride) {
    return Mapping.resolveEmployeeMealAssignment(ou(orgUnit), floorOverride, null, INDEX);
  }

  @Nested
  @DisplayName("seed data shape")
  class Shape {
    @Test
    @DisplayName("has a 6-level org tree (BR-61) with every level used")
    void sixLevels() {
      assertThat(NODES.stream().map(SeedOrgTree.Node::level).collect(Collectors.toSet()))
          .containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
    }

    @Test
    @DisplayName("has 4 floors, 10 cost centers and exactly 1,700 uniquely coded employees")
    void counts() {
      assertThat(SeedOrgTree.FLOORS).hasSize(4);
      assertThat(SeedOrgTree.COST_CENTERS).hasSize(10);
      assertThat(EMPLOYEES).hasSize(SeedEmployees.COUNT);
      assertThat(new HashSet<>(EMPLOYEES.stream().map(SeedEmployees.Person::employeeCode).toList()))
          .hasSize(SeedEmployees.COUNT);
      assertThat(new HashSet<>(EMPLOYEES.stream().map(SeedEmployees.Person::email).toList()))
          .hasSize(SeedEmployees.COUNT);
    }

    @Test
    @DisplayName("is deterministic across runs, and equal to the legacy seed")
    void deterministic() {
      assertThat(SeedEmployees.build(SeedOrgTree.build())).isEqualTo(EMPLOYEES);
      // Rows read back from a database seeded by the legacy API (same PRNG, same seed).
      assertThat(EMPLOYEES.get(0))
          .isEqualTo(
              new SeedEmployees.Person(
                  "PNJP000001",
                  "Nguyễn Văn An",
                  "an.nguyen@pnjp.test",
                  "ou_XCT-A1-1",
                  null,
                  "2024-12-17"));
      assertThat(EMPLOYEES.get(7))
          .isEqualTo(
              new SeedEmployees.Person(
                  "PNJP000008",
                  "Đỗ Văn Hùng",
                  "hung.do8@pnjp.test",
                  "ou_XCT-A",
                  null,
                  "2013-05-03"));
      assertThat(EMPLOYEES.get(998))
          .isEqualTo(
              new SeedEmployees.Person(
                  "PNJP000999",
                  "Phan Ngọc Yến",
                  "yen.phan999@pnjp.test",
                  "ou_XKH-DUC-2",
                  null,
                  "2025-04-14"));
      assertThat(EMPLOYEES.get(1699))
          .isEqualTo(
              new SeedEmployees.Person(
                  "PNJP001700",
                  "Bùi Ngọc Lan",
                  "lan.bui1700@pnjp.test",
                  "ou_KHO-XH",
                  null,
                  "2023-05-12"));
    }

    @Test
    @DisplayName("stores a materialized path ending in the node id for every node")
    void paths() {
      for (SeedOrgTree.Node n : NODES) {
        assertThat(n.path()).startsWith("/").endsWith("/" + n.id() + "/");
      }
    }

    @Test
    @DisplayName("maps only part of the tree directly, so inheritance is exercised (BR-62)")
    void partial() {
      long withFloor = NODES.stream().filter(n -> n.floorId() != null).count();
      long withCostCenter = NODES.stream().filter(n -> n.costCenterId() != null).count();
      assertThat(withFloor).isPositive().isLessThan(NODES.size() / 2);
      assertThat(withCostCenter).isLessThan(NODES.size() / 2);
      assertThat(NODES.stream().filter(n -> n.canteenId() != null)).hasSize(1); // only the root
    }
  }

  @Nested
  @DisplayName("BR-62 mapping resolution on the 6-level seed tree")
  class Inheritance {
    @Test
    @DisplayName("BR-62: a level-6 node inherits the canteen from the root")
    void canteen() {
      assertThat(Mapping.resolve(ou("XCT-A1-1"), Field.CANTEEN, INDEX))
          .isEqualTo(new Mapping.Resolved("ct_gv", ou("PNJP-GV"), true));
    }

    @Test
    @DisplayName(
        "BR-62: the nearest ancestor wins (Tổ Dây chuyền floor T3 beats Xưởng Chế tác floor T2)")
    void nearest() {
      Mapping.Resolved floor = Mapping.resolve(ou("XCT-A2-1"), Field.FLOOR, INDEX);
      assertThat(floor.value()).isEqualTo("fl_T3");
      assertThat(floor.sourceNodeId()).isEqualTo(ou("XCT-A2"));
      assertThat(Mapping.resolve(ou("XCT-A1-1"), Field.FLOOR, INDEX).value()).isEqualTo("fl_T2");
    }

    @Test
    @DisplayName("BR-62: Hành chánh overrides the cost center its parent block inherits")
    void costCenter() {
      assertThat(Mapping.resolve(ou("NS-HC"), Field.COST_CENTER, INDEX).value())
          .isEqualTo("cc_CC02");
      assertThat(Mapping.resolve(ou("NS-TD"), Field.COST_CENTER, INDEX).value())
          .isEqualTo("cc_CC01");
    }
  }

  @Nested
  @DisplayName("BR-60 / BR-63 on the seed tree")
  class MealUnits {
    @Test
    @DisplayName("BR-60: a level-6 worker belongs to the nearest flagged node above (Tổ Nhẫn)")
    void nearestFlagged() {
      assertThat(Mapping.resolveMealUnitId(ou("XCT-A1-1"), INDEX)).isEqualTo(ou("XCT-A1"));
    }

    @Test
    @DisplayName("BR-63: floor and canteen come from the meal unit, not the employee node")
    void fromMealUnit() {
      assertThat(Mapping.resolveMealUnitMapping(ou("XCT-A2"), INDEX))
          .isEqualTo(new Mapping.MealUnitMapping(true, "fl_T3", "ct_gv", List.of()));
    }

    @Test
    @DisplayName("BR-63: the Sửa chữa meal unit has no floor, so it is invalid")
    void invalid() {
      assertThat(Mapping.resolveMealUnitMapping(ou("XSC-1"), INDEX).missing())
          .containsExactly("floor");
      EmployeeMealAssignment a = assignmentOf("XSC-1", null);
      assertThat(a.status()).isEqualTo(MealStatus.INVALID_MEAL_UNIT);
      assertThat(a.canRegister()).isFalse();
    }

    @Test
    @DisplayName("BR-60: Kho vận employees have no flagged ancestor, so they are unassigned")
    void unassigned() {
      EmployeeMealAssignment a = assignmentOf("KHO-XH", null);
      assertThat(a.status()).isEqualTo(MealStatus.NO_MEAL_UNIT);
      assertThat(a.canRegister()).isFalse();
      assertThat(a.missingCostCenter()).isTrue();
    }

    @Test
    @DisplayName(
        "BR-63: cost center follows the org tree; an employee pick-up floor beats the meal unit"
            + " floor")
    void override() {
      EmployeeMealAssignment base = assignmentOf("XCT-A1-1", null);
      assertThat(base.costCenterId()).isEqualTo("cc_CC03");
      assertThat(base.costCenterSource()).isEqualTo(CostCenterSource.MAPPING);
      assertThat(base.floorId()).isEqualTo("fl_T2");
      assertThat(base.floorSource()).isEqualTo(FloorSource.MEAL_UNIT);
      EmployeeMealAssignment moved = assignmentOf("XCT-A1-1", "fl_T1");
      assertThat(moved.costCenterId()).isEqualTo("cc_CC03");
      assertThat(moved.floorId()).isEqualTo("fl_T1");
      assertThat(moved.floorSource()).isEqualTo(FloorSource.OVERRIDE);
    }

    @Test
    @DisplayName("every seeded employee resolves, with a plausible mix of outcomes")
    void mix() {
      Map<MealStatus, Integer> counts = new EnumMap<>(MealStatus.class);
      for (SeedEmployees.Person e : EMPLOYEES) {
        counts.merge(
            Mapping.resolveEmployeeMealAssignment(e.orgUnitId(), null, null, INDEX).status(),
            1,
            Integer::sum);
      }
      assertThat(counts.get(MealStatus.OK)).isGreaterThan(1400);
      assertThat(counts.get(MealStatus.NO_MEAL_UNIT)).isPositive();
      assertThat(counts.get(MealStatus.INVALID_MEAL_UNIT)).isPositive();
    }

    @Test
    @DisplayName("puts the demo accounts where the docs say")
    void demoAccounts() {
      Map<String, String> where =
          EMPLOYEES.stream()
              .collect(
                  Collectors.toMap(
                      SeedEmployees.Person::employeeCode, SeedEmployees.Person::orgUnitId));
      assertThat(where.get(Demo.EMPLOYEE)).isEqualTo(ou("XCT-A1-1"));
      assertThat(where.get(Demo.UNASSIGNED)).isEqualTo(ou("KHO-XH"));
      assertThat(where.get(Demo.INVALID_MEAL_UNIT)).isEqualTo(ou("XSC-1"));
    }
  }
}
