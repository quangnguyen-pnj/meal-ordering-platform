package vn.pnj.meal.api.close;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import vn.pnj.meal.api.attendance.AttendanceService;
import vn.pnj.meal.api.registrations.RegistrationViews.TodayRow;

/** Responses of /admin/reconciliation and /admin/adjustments (BR-40 to BR-46). */
public final class CloseViews {
  private CloseViews() {}

  /**
   * One meal of day D (BR-40): forecast, actual, difference and status, and the BR-42 warning.
   * After finalization {@code actual} is the final count; {@code adjustment} and {@code effective}
   * follow BR-46.
   *
   * @param deviationPct null when the meal has no forecast
   */
  public record ReconciliationMeal(
      String menuItemId,
      String displayName,
      String canteenId,
      String canteenName,
      int forecast,
      int actual,
      int difference,
      @Schema(allowableValues = {"MATCHED", "MISMATCHED"}) String status,
      boolean deviates,
      @Nullable Double deviationPct,
      @Nullable Integer finalCount,
      @Nullable Integer adjustment,
      @Nullable Integer effective) {}

  /**
   * BR-40 summary cards.
   *
   * @param cases employees with at least one case to review (BR-41)
   */
  public record ReconciliationSummary(
      int forecast,
      int actual,
      int difference,
      int cases,
      int deviatingMeals,
      @Nullable Integer finalCount,
      @Nullable Integer adjustment,
      @Nullable Integer effective) {}

  /**
   * /admin/reconciliation for day D, filtered by the caller's scope.
   *
   * @param canFinalize BR-43: the day is open for finalization now (the button also needs the
   *     day.finalize permission)
   * @param canAdjust BR-46: finalized and before {@code close.adjustmentDeadline}
   * @param cutoffHeld BR-34: the cutoff kept every meal; finalizing now counts them all
   * @param exceptions BR-41: employees with cases, with their forecast and actual meals
   */
  public record ReconciliationView(
      LocalDate date,
      Instant now,
      boolean servingDay,
      boolean forecastTaken,
      @Nullable ClosedView closed,
      boolean canFinalize,
      Instant finalizeOpensAt,
      Instant autoFinalizeAt,
      Instant adjustmentDeadlineAt,
      boolean canAdjust,
      boolean cutoffHeld,
      double deviationThresholdPct,
      AttendanceService.AttendanceStatus attendance,
      ReconciliationSummary summary,
      List<ReconciliationMeal> meals,
      List<TodayRow> exceptions) {}

  public record ForecastMeal(String displayName, int quantity) {}

  public record ForecastDay(LocalDate date, int total, List<ForecastMeal> meals) {}

  /**
   * @param closedAt BR-07: when the forecast snapshot was taken; null while registration is open
   */
  public record ForecastCanteen(
      String canteenId, String canteenName, @Nullable Instant closedAt, List<ForecastDay> days) {}

  /** The weekly forecast tab (BR-07, BR-44). */
  public record ForecastWeekView(
      LocalDate weekStart, boolean exportable, List<ForecastCanteen> canteens) {}

  // --- adjustments (BR-46) ---------------------------------------------------------------------

  public record AdjustmentLineView(
      String employeeCode,
      String employeeName,
      String menuItemId,
      String displayName,
      int delta,
      @Nullable String mealUnitName) {}

  public record AdjustmentView(
      String id,
      int number,
      Instant createdAt,
      String createdByName,
      String reason,
      boolean catererConfirmed,
      int totalDelta,
      List<AdjustmentLineView> lines) {}

  /**
   * @param adjustmentDeadlineAt BR-46: no adjustment from then on
   */
  public record AdjustmentsView(
      LocalDate date,
      @Nullable ClosedView closed,
      boolean canAdjust,
      Instant adjustmentDeadlineAt,
      List<AdjustmentView> items) {}

  /** One meal of an employee's finalized day, for the adjustment form. */
  public record EmployeeDayMeal(
      String menuItemId,
      String displayName,
      boolean onMenu,
      int finalQuantity,
      int adjusted,
      int effective) {}

  public record EmployeeDayView(
      String employeeCode,
      String name,
      String orgPath,
      @Nullable String canteenName,
      List<EmployeeDayMeal> meals) {}

  public record AdjustmentCreated(String id, int number, int totalDelta) {}
}
