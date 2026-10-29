package vn.pnj.meal.api.employees;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;
import vn.pnj.meal.rules.org.Mapping;
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
    @Schema(allowableValues = {"ACTIVE", "LEFT"}) String status,
    String orgUnitId,
    String orgPath,
    @Nullable String mealUnitId,
    @Nullable String mealUnitName,
    MealStatus mealStatus,
    @ArraySchema(
            schema = @Schema(allowableValues = {Mapping.MISSING_FLOOR, Mapping.MISSING_CANTEEN}))
        List<String> missing,
    @Nullable String floorId,
    @Nullable String floorName,
    @Nullable FloorSource floorSource,
    @Nullable String floorOverrideId,
    @Nullable String canteenId,
    @Nullable String canteenName,
    @Nullable String costCenterId,
    @Nullable String costCenterName,
    @Nullable CostCenterSource costCenterSource,
    List<String> roles) {}
