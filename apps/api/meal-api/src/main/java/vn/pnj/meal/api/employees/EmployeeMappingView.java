package vn.pnj.meal.api.employees;

import java.util.List;
import vn.pnj.meal.rules.org.Mapping.CostCenterSource;
import vn.pnj.meal.rules.org.Mapping.FloorSource;
import vn.pnj.meal.rules.org.Mapping.MealStatus;

/**
 * One employee in /admin/employees with the resolved meal unit, floor, canteen and cost center
 * (BR-60, BR-63); the shape of {@code EmployeeMappingView} in packages/shared.
 *
 * @param orgPath "Khối Sản xuất / Xưởng Chế tác / ..." (BR-61: screens show the full path)
 * @param floorSource OVERRIDE: the HC admin set this employee's pick-up floor
 * @param costCenterSource MAPPING follows the org tree; DMP is the default when no node has one
 */
public record EmployeeMappingView(
    String employeeCode,
    String name,
    String status,
    String orgUnitId,
    String orgPath,
    String mealUnitId,
    String mealUnitName,
    MealStatus mealStatus,
    List<String> missing,
    String floorId,
    String floorName,
    FloorSource floorSource,
    String floorOverrideId,
    String canteenId,
    String canteenName,
    String costCenterId,
    String costCenterName,
    CostCenterSource costCenterSource,
    List<String> roles) {}
