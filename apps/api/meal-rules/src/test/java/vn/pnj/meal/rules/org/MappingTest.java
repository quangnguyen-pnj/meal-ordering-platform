package vn.pnj.meal.rules.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.rules.org.Mapping.CostCenterSource;
import vn.pnj.meal.rules.org.Mapping.EmployeeMealAssignment;
import vn.pnj.meal.rules.org.Mapping.Field;
import vn.pnj.meal.rules.org.Mapping.FloorSource;
import vn.pnj.meal.rules.org.Mapping.MealStatus;
import vn.pnj.meal.rules.org.Mapping.MealUnitMapping;
import vn.pnj.meal.rules.org.Mapping.Resolved;
import vn.pnj.meal.rules.org.Mapping.ResolvedCostCenter;

/**
 * A 6-level chain L1 > L2 > L3 > L4 > L5 > L6 with sparse mapping: L1 canteen C1; L2 floor F1 and
 * cost center K1; L4 floor F2 (overrides L2) and meal unit; L6 nothing. Plus a sibling branch L3b
 * with no floor and no meal unit, and a second root L1x with nothing mapped.
 */
class MappingTest {
  private static OrgNode node(String id, String parentId) {
    return new OrgNode(id, parentId, false, null, null, null);
  }

  private static final OrgIndex INDEX =
      OrgIndex.of(
          List.of(
              new OrgNode("L1", null, false, null, "C1", null),
              new OrgNode("L2", "L1", false, "F1", null, "K1"),
              node("L3", "L2"),
              new OrgNode("L4", "L3", true, "F2", null, null),
              node("L5", "L4"),
              node("L6", "L5"),
              node("L3b", "L2"),
              node("L4b", "L3b"),
              node("L1x", null),
              new OrgNode("L2x", "L1x", true, null, null, null)));

  @Nested
  @DisplayName("BR-62: mapping inheritance")
  class Inheritance {
    @Test
    @DisplayName("BR-62: nearest ancestor wins over a farther one")
    void nearestWins() {
      assertThat(Mapping.resolve("L6", Field.FLOOR, INDEX))
          .isEqualTo(new Resolved("F2", "L4", true));
    }

    @Test
    @DisplayName("BR-62: a node with its own value is not inherited")
    void ownValue() {
      assertThat(Mapping.resolve("L4", Field.FLOOR, INDEX))
          .isEqualTo(new Resolved("F2", "L4", false));
    }

    @Test
    @DisplayName("BR-62: a branch without its own value inherits from the farther ancestor")
    void fartherAncestor() {
      assertThat(Mapping.resolve("L4b", Field.FLOOR, INDEX).value()).isEqualTo("F1");
      Resolved canteen = Mapping.resolve("L6", Field.CANTEEN, INDEX);
      assertThat(canteen.value()).isEqualTo("C1");
      assertThat(canteen.sourceNodeId()).isEqualTo("L1");
    }

    @Test
    @DisplayName("BR-62: resolves to null when nothing up the tree is mapped")
    void nothing() {
      assertThat(Mapping.resolve("L2x", Field.FLOOR, INDEX))
          .isEqualTo(new Resolved(null, null, false));
    }

    @Test
    @DisplayName("BR-61: detects a cycle instead of looping forever")
    void cycle() {
      OrgIndex cyclic = OrgIndex.of(List.of(node("a", "b"), node("b", "a")));
      assertThatThrownBy(() -> Mapping.resolve("a", Field.FLOOR, cyclic))
          .hasMessageContaining("cycle");
    }

    @Test
    @DisplayName("rejects an unknown node id")
    void unknown() {
      assertThatThrownBy(() -> Mapping.resolve("nope", Field.FLOOR, INDEX))
          .hasMessageContaining("Unknown org node");
    }
  }

  @Nested
  @DisplayName("BR-60: meal unit resolution")
  class MealUnit {
    @Test
    @DisplayName("BR-60: an employee belongs to the nearest flagged node at or above their own")
    void nearestFlagged() {
      assertThat(Mapping.resolveMealUnitId("L6", INDEX)).isEqualTo("L4");
      assertThat(Mapping.resolveMealUnitId("L4", INDEX)).isEqualTo("L4");
    }

    @Test
    @DisplayName("BR-60: no flagged node at or above means no meal unit")
    void noneFlagged() {
      assertThat(Mapping.resolveMealUnitId("L3b", INDEX)).isNull();
      assertThat(Mapping.resolveMealUnitId("L4b", INDEX)).isNull();
    }
  }

  @Nested
  @DisplayName("BR-63: meal unit validity")
  class Validity {
    @Test
    @DisplayName("BR-63: floor and canteen come from the meal unit, resolved through inheritance")
    void valid() {
      assertThat(Mapping.resolveMealUnitMapping("L4", INDEX))
          .isEqualTo(new MealUnitMapping(true, "F2", "C1", List.of()));
    }

    @Test
    @DisplayName("BR-63: a meal unit that resolves no floor or no canteen is invalid")
    void invalid() {
      assertThat(Mapping.resolveMealUnitMapping("L2x", INDEX).missing())
          .containsExactly("floor", "canteen");
      OrgIndex noFloor =
          OrgIndex.of(
              List.of(
                  new OrgNode("r", null, false, null, "C1", null),
                  new OrgNode("mu", "r", true, null, null, null)));
      assertThat(Mapping.resolveMealUnitMapping("mu", noFloor))
          .isEqualTo(new MealUnitMapping(false, null, null, List.of("floor")));
      OrgIndex noCanteen =
          OrgIndex.of(
              List.of(
                  new OrgNode("r", null, false, "F1", null, null),
                  new OrgNode("mu", "r", true, null, null, null)));
      assertThat(Mapping.resolveMealUnitMapping("mu", noCanteen).missing())
          .containsExactly("canteen");
    }
  }

  @Nested
  @DisplayName("BR-63: employee cost center")
  class CostCenter {
    @Test
    @DisplayName("BR-63: the cost center follows the org tree: the nearest node with one applies")
    void followsTree() {
      assertThat(Mapping.resolveEmployeeCostCenter("L6", null, INDEX))
          .isEqualTo(new ResolvedCostCenter("K1", CostCenterSource.MAPPING));
    }

    @Test
    @DisplayName("BR-62: an admin mapping overrides the cost center DMP supplied")
    void mappingBeatsDmp() {
      assertThat(Mapping.resolveEmployeeCostCenter("L6", "K5", INDEX))
          .isEqualTo(new ResolvedCostCenter("K1", CostCenterSource.MAPPING));
    }

    @Test
    @DisplayName("BR-62: the DMP cost center is the default when no mapping exists")
    void dmpDefault() {
      assertThat(Mapping.resolveEmployeeCostCenter("L2x", "K5", INDEX))
          .isEqualTo(new ResolvedCostCenter("K5", CostCenterSource.DMP));
    }

    @Test
    @DisplayName("BR-63: no mapping or DMP value leaves the cost center empty")
    void empty() {
      assertThat(Mapping.resolveEmployeeCostCenter("L2x", null, INDEX))
          .isEqualTo(new ResolvedCostCenter(null, null));
    }
  }

  @Nested
  @DisplayName("BR-60/BR-63: employee meal assignment")
  class Assignment {
    @Test
    @DisplayName("BR-60: a registrable employee gets meal unit, floor, canteen and cost center")
    void registrable() {
      EmployeeMealAssignment a = Mapping.resolveEmployeeMealAssignment("L6", null, null, INDEX);
      assertThat(a.mealUnitId()).isEqualTo("L4");
      assertThat(a.status()).isEqualTo(MealStatus.OK);
      assertThat(a.floorId()).isEqualTo("F2");
      assertThat(a.canteenId()).isEqualTo("C1");
      assertThat(a.costCenterId()).isEqualTo("K1");
      assertThat(a.canRegister()).isTrue();
      assertThat(a.missingCostCenter()).isFalse();
    }

    @Test
    @DisplayName(
        "BR-60: with no fallback meal unit set, an employee with no meal unit cannot register")
    void noMealUnit() {
      EmployeeMealAssignment a = Mapping.resolveEmployeeMealAssignment("L4b", null, null, INDEX);
      assertThat(a.mealUnitId()).isNull();
      assertThat(a.status()).isEqualTo(MealStatus.NO_MEAL_UNIT);
      assertThat(a.canRegister()).isFalse();
    }

    @Test
    @DisplayName(
        "BR-63: with no fallback meal unit set, an employee under an invalid one cannot register")
    void invalidMealUnit() {
      EmployeeMealAssignment a = Mapping.resolveEmployeeMealAssignment("L2x", null, null, INDEX);
      assertThat(a.mealUnitId()).isNull();
      assertThat(a.ownMealUnitId()).isEqualTo("L2x");
      assertThat(a.status()).isEqualTo(MealStatus.INVALID_MEAL_UNIT);
      assertThat(a.missing()).containsExactly("floor", "canteen");
      assertThat(a.canRegister()).isFalse();
      assertThat(a.missingCostCenter()).isTrue();
    }

    @Test
    @DisplayName("BR-63: an HC admin can move one employee to another pick-up floor")
    void floorOverride() {
      EmployeeMealAssignment moved = Mapping.resolveEmployeeMealAssignment("L6", "F9", null, INDEX);
      assertThat(moved.mealUnitId()).isEqualTo("L4");
      assertThat(moved.status()).isEqualTo(MealStatus.OK);
      assertThat(moved.floorId()).isEqualTo("F9");
      assertThat(moved.floorSource()).isEqualTo(FloorSource.OVERRIDE);
      assertThat(moved.canteenId()).isEqualTo("C1");
      assertThat(moved.canRegister()).isTrue();
      EmployeeMealAssignment stays = Mapping.resolveEmployeeMealAssignment("L6", null, null, INDEX);
      assertThat(stays.floorId()).isEqualTo("F2");
      assertThat(stays.floorSource()).isEqualTo(FloorSource.MEAL_UNIT);
    }

    @Test
    @DisplayName("BR-63: an employee floor does not give a canteen; the meal unit still needs one")
    void floorWithoutCanteen() {
      EmployeeMealAssignment a = Mapping.resolveEmployeeMealAssignment("L2x", "F9", null, INDEX);
      assertThat(a.status()).isEqualTo(MealStatus.INVALID_MEAL_UNIT);
      assertThat(a.missing()).containsExactly("canteen");
      assertThat(a.canRegister()).isFalse();
    }

    @Test
    @DisplayName("BR-63: an employee without a cost center can still register")
    void noCostCenter() {
      OrgIndex noCost =
          OrgIndex.of(List.of(new OrgNode("r", null, true, "F1", "C1", null), node("e", "r")));
      EmployeeMealAssignment a = Mapping.resolveEmployeeMealAssignment("e", null, null, noCost);
      assertThat(a.canRegister()).isTrue();
      assertThat(a.missingCostCenter()).isTrue();
      assertThat(a.costCenterId()).isNull();
    }
  }

  /**
   * CR 2026-11-04: the same tree as {@link #INDEX} plus a valid meal unit FB (floor F7, canteen C1)
   * picked as the fallback, a canteen C2 on L3b, and a meal unit M3 under L3b with a floor (F3) but
   * whose canteen comes from L3b.
   */
  @Nested
  @DisplayName("BR-60/BR-63 (CR 2026-11-04): fallback meal unit")
  class Fallback {
    private final OrgIndex withFallback =
        OrgIndex.of(
            List.of(
                new OrgNode("L1", null, false, null, "C1", null),
                new OrgNode("L2", "L1", false, "F1", "C1", "K1"),
                new OrgNode("FB", "L2", true, "F7", null, null),
                new OrgNode("L4", "L2", true, "F2", null, null),
                node("L6", "L4"),
                new OrgNode("L3b", "L2", false, null, "C2", null),
                node("L4b", "L3b"),
                new OrgNode("L1y", null, false, null, null, null),
                new OrgNode("L2y", "L1y", true, null, null, null),
                node("L3y", "L1y"),
                new OrgNode("L1z", null, true, null, "C3", null),
                node("L2z", "L1z")),
            "FB");

    @Test
    @DisplayName(
        "BR-60: an employee with a canteen but no meal unit registers into the fallback meal unit")
    void noMealUnitGoesToFallback() {
      OrgIndex index =
          OrgIndex.of(
              List.of(
                  new OrgNode("R", null, false, "F1", "C1", "K1"),
                  new OrgNode("FB", "R", true, "F7", null, null),
                  node("D", "R")),
              "FB");
      EmployeeMealAssignment a = Mapping.resolveEmployeeMealAssignment("D", null, null, index);
      assertThat(a.canRegister()).isTrue();
      assertThat(a.viaFallback()).isTrue();
      assertThat(a.status()).isEqualTo(MealStatus.NO_MEAL_UNIT);
      assertThat(a.ownMealUnitId()).isNull();
      assertThat(a.mealUnitId()).isEqualTo("FB");
      assertThat(a.floorId()).isEqualTo("F7");
      assertThat(a.canteenId()).isEqualTo("C1");
      assertThat(a.costCenterId()).isEqualTo("K1");
    }

    @Test
    @DisplayName(
        "BR-15: the menu canteen is the department's own, even when the fallback's differs")
    void departmentCanteen() {
      EmployeeMealAssignment a =
          Mapping.resolveEmployeeMealAssignment("L4b", null, null, withFallback);
      assertThat(a.viaFallback()).isTrue();
      assertThat(a.mealUnitId()).isEqualTo("FB");
      assertThat(a.canteenId()).isEqualTo("C2");
      assertThat(a.floorId()).isEqualTo("F7");
    }

    @Test
    @DisplayName(
        "BR-63: an employee whose meal unit has a canteen but no floor goes to the fallback")
    void invalidUnitWithCanteen() {
      EmployeeMealAssignment a =
          Mapping.resolveEmployeeMealAssignment("L2z", null, null, withFallback);
      assertThat(a.status()).isEqualTo(MealStatus.INVALID_MEAL_UNIT);
      assertThat(a.missing()).containsExactly("floor");
      assertThat(a.ownMealUnitId()).isEqualTo("L1z");
      assertThat(a.mealUnitId()).isEqualTo("FB");
      assertThat(a.canteenId()).isEqualTo("C3");
      assertThat(a.canRegister()).isTrue();
    }

    @Test
    @DisplayName("BR-63: an employee whose department has no canteen cannot register")
    void noCanteen() {
      EmployeeMealAssignment noUnit =
          Mapping.resolveEmployeeMealAssignment("L3y", null, null, withFallback);
      assertThat(noUnit.canRegister()).isFalse();
      assertThat(noUnit.viaFallback()).isFalse();
      assertThat(noUnit.mealUnitId()).isNull();
      assertThat(noUnit.canteenId()).isNull();
      EmployeeMealAssignment invalidUnit =
          Mapping.resolveEmployeeMealAssignment("L2y", null, null, withFallback);
      assertThat(invalidUnit.canRegister()).isFalse();
      assertThat(invalidUnit.missing()).containsExactly("floor", "canteen");
    }

    @Test
    @DisplayName("BR-63: a valid meal unit keeps its employees; the fallback does not apply")
    void validUnitUnchanged() {
      EmployeeMealAssignment a =
          Mapping.resolveEmployeeMealAssignment("L6", null, null, withFallback);
      assertThat(a.viaFallback()).isFalse();
      assertThat(a.mealUnitId()).isEqualTo("L4");
      assertThat(a.floorId()).isEqualTo("F2");
    }

    @Test
    @DisplayName("BR-63: an employee floor override applies at the fallback meal unit too")
    void floorOverride() {
      EmployeeMealAssignment a =
          Mapping.resolveEmployeeMealAssignment("L4b", "F9", null, withFallback);
      assertThat(a.mealUnitId()).isEqualTo("FB");
      assertThat(a.floorId()).isEqualTo("F9");
      assertThat(a.floorSource()).isEqualTo(FloorSource.OVERRIDE);
    }

    @Test
    @DisplayName(
        "BR-60: the fallback serves every canteen; it needs a floor but no canteen of its own")
    void fallbackWithoutCanteen() {
      OrgIndex index =
          OrgIndex.of(
              List.of(
                  node("R", null),
                  new OrgNode("FB", "R", true, "F7", null, null),
                  new OrgNode("A", "R", false, null, "CA", null),
                  new OrgNode("B", "R", false, null, "CB", null)),
              "FB");
      EmployeeMealAssignment a = Mapping.resolveEmployeeMealAssignment("A", null, null, index);
      EmployeeMealAssignment b = Mapping.resolveEmployeeMealAssignment("B", null, null, index);
      assertThat(List.of(a, b)).allMatch(x -> x.canRegister() && "FB".equals(x.mealUnitId()));
      assertThat(List.of(a.floorId(), b.floorId())).containsOnly("F7");
      assertThat(a.canteenId()).isEqualTo("CA");
      assertThat(b.canteenId()).isEqualTo("CB");
    }

    @Test
    @DisplayName("BR-60: a fallback that is no longer a meal unit, or has no floor, is ignored")
    void unusableFallback() {
      OrgIndex notMealUnit =
          OrgIndex.of(
              List.of(
                  new OrgNode("R", null, false, "F1", "C1", null),
                  new OrgNode("FB", "R", false, null, null, null),
                  node("D", "R")),
              "FB");
      assertThat(notMealUnit.fallbackMealUnitId()).isNull();
      assertThat(Mapping.resolveEmployeeMealAssignment("D", null, null, notMealUnit).canRegister())
          .isFalse();
      OrgIndex noFloor =
          OrgIndex.of(
              List.of(
                  new OrgNode("R", null, false, null, "C1", null),
                  new OrgNode("FB", "R", true, null, null, null),
                  node("D", "R")),
              "FB");
      assertThat(Mapping.resolveEmployeeMealAssignment("D", null, null, noFloor).canRegister())
          .isFalse();
    }
  }
}
