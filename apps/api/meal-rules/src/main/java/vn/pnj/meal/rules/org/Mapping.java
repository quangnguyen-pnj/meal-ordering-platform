package vn.pnj.meal.rules.org;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Org-tree mapping resolution (BR-60, BR-62, BR-63).
 *
 * <ul>
 *   <li>BR-62: a node without a floor / canteen / cost center inherits it from the nearest ancestor
 *       that has one.
 *   <li>BR-60: an employee belongs to the nearest meal-unit node at or above their own node.
 *   <li>BR-63: floor and canteen come from the employee's meal unit; a meal unit that resolves no
 *       floor or no canteen is invalid. An HC admin may set the floor where one employee picks up
 *       their meal (product owner, 2026-10-23). Cost center follows the org tree: the inherited
 *       mapping, else the value supplied by DMP.
 * </ul>
 */
public final class Mapping {
  public static final String MISSING_FLOOR = "floor";
  public static final String MISSING_CANTEEN = "canteen";

  private Mapping() {}

  public enum Field {
    FLOOR(OrgNode::floorId),
    CANTEEN(OrgNode::canteenId),
    COST_CENTER(OrgNode::costCenterId);

    private final Function<OrgNode, String> read;

    Field(Function<OrgNode, String> read) {
      this.read = read;
    }
  }

  /**
   * @param value the effective id, or null when neither the node nor any ancestor has one
   * @param sourceNodeId node that supplies the value (the node itself when not inherited)
   */
  public record Resolved(String value, String sourceNodeId, boolean inherited) {
    static final Resolved NONE = new Resolved(null, null, false);
  }

  /** BR-62: nearest ancestor (including the node itself) that has a value wins. */
  public static Resolved resolve(String nodeId, Field field, OrgIndex index) {
    for (OrgNode node : index.nodeAndAncestors(nodeId)) {
      String value = field.read.apply(node);
      if (value != null) {
        return new Resolved(value, node.id(), !node.id().equals(nodeId));
      }
    }
    return Resolved.NONE;
  }

  /** BR-60: nearest node at or above {@code nodeId} flagged as a meal unit, or null. */
  public static String resolveMealUnitId(String nodeId, OrgIndex index) {
    for (OrgNode node : index.nodeAndAncestors(nodeId)) {
      if (node.isMealUnit()) {
        return node.id();
      }
    }
    return null;
  }

  /**
   * BR-63: a meal unit is valid only when both a floor and a canteen resolve for it.
   *
   * @param missing which of "floor" / "canteen" did not resolve; empty when valid
   */
  public record MealUnitMapping(
      boolean valid, String floorId, String canteenId, List<String> missing) {}

  public static MealUnitMapping resolveMealUnitMapping(String mealUnitId, OrgIndex index) {
    String floor = resolve(mealUnitId, Field.FLOOR, index).value();
    String canteen = resolve(mealUnitId, Field.CANTEEN, index).value();
    if (floor != null && canteen != null) {
      return new MealUnitMapping(true, floor, canteen, List.of());
    }
    return new MealUnitMapping(false, null, null, missing(floor, canteen));
  }

  public enum CostCenterSource {
    MAPPING,
    DMP
  }

  public record ResolvedCostCenter(String costCenterId, CostCenterSource source) {}

  /**
   * BR-63 / BR-62: an employee's cost center follows the org tree (product owner, 2026-10-23): the
   * nearest node at or above the employee that has one, then the cost center DMP supplied for the
   * employee as the default.
   */
  public static ResolvedCostCenter resolveEmployeeCostCenter(
      String orgUnitId, String dmpCostCenterId, OrgIndex index) {
    Resolved inherited = resolve(orgUnitId, Field.COST_CENTER, index);
    if (inherited.value() != null) {
      return new ResolvedCostCenter(inherited.value(), CostCenterSource.MAPPING);
    }
    if (dmpCostCenterId != null) {
      return new ResolvedCostCenter(dmpCostCenterId, CostCenterSource.DMP);
    }
    return new ResolvedCostCenter(null, null);
  }

  public enum MealStatus {
    OK,
    NO_MEAL_UNIT,
    INVALID_MEAL_UNIT
  }

  public enum FloorSource {
    MEAL_UNIT,
    /** The HC admin moved this employee to another pick-up floor. */
    OVERRIDE
  }

  /**
   * @param mealUnitId nearest flagged node (BR-60), even when it turns out invalid
   * @param missing which of "floor" / "canteen" the meal unit could not resolve
   * @param canRegister false for employees on the "unassigned" list (BR-60, BR-63)
   * @param missingCostCenter BR-63: they can still register and appear on a separate list
   */
  public record EmployeeMealAssignment(
      String mealUnitId,
      MealStatus status,
      List<String> missing,
      String floorId,
      FloorSource floorSource,
      String canteenId,
      String costCenterId,
      CostCenterSource costCenterSource,
      boolean canRegister,
      boolean missingCostCenter) {}

  public static EmployeeMealAssignment resolveEmployeeMealAssignment(
      String orgUnitId, String floorOverrideId, String dmpCostCenterId, OrgIndex index) {
    ResolvedCostCenter cost = resolveEmployeeCostCenter(orgUnitId, dmpCostCenterId, index);
    boolean missingCostCenter = cost.costCenterId() == null;

    String mealUnitId = resolveMealUnitId(orgUnitId, index);
    if (mealUnitId == null) {
      return new EmployeeMealAssignment(
          null,
          MealStatus.NO_MEAL_UNIT,
          List.of(),
          null,
          null,
          null,
          cost.costCenterId(),
          cost.source(),
          false,
          missingCostCenter);
    }

    // The meal unit still decides the canteen (BR-15); an employee floor stands in for a floor
    // the unit lacks, so that employee can register while the rest of the unit cannot.
    String unitFloor = resolve(mealUnitId, Field.FLOOR, index).value();
    String canteen = resolve(mealUnitId, Field.CANTEEN, index).value();
    String floor = floorOverrideId != null ? floorOverrideId : unitFloor;
    if (floor == null || canteen == null) {
      return new EmployeeMealAssignment(
          mealUnitId,
          MealStatus.INVALID_MEAL_UNIT,
          missing(floor, canteen),
          null,
          null,
          null,
          cost.costCenterId(),
          cost.source(),
          false,
          missingCostCenter);
    }
    return new EmployeeMealAssignment(
        mealUnitId,
        MealStatus.OK,
        List.of(),
        floor,
        floorOverrideId != null ? FloorSource.OVERRIDE : FloorSource.MEAL_UNIT,
        canteen,
        cost.costCenterId(),
        cost.source(),
        true,
        missingCostCenter);
  }

  private static List<String> missing(String floor, String canteen) {
    List<String> missing = new ArrayList<>(2);
    if (floor == null) {
      missing.add(MISSING_FLOOR);
    }
    if (canteen == null) {
      missing.add(MISSING_CANTEEN);
    }
    return List.copyOf(missing);
  }
}
