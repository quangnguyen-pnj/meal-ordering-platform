package vn.pnj.meal.api.close;

import static vn.pnj.meal.api.menu.MenuController.date;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.pnj.meal.api.access.RequestPrincipal;
import vn.pnj.meal.api.access.RequirePermission;
import vn.pnj.meal.api.access.ScopeType;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.close.CloseViews.AdjustmentCreated;
import vn.pnj.meal.api.close.CloseViews.AdjustmentsView;
import vn.pnj.meal.api.close.CloseViews.EmployeeDayView;
import vn.pnj.meal.api.close.CloseViews.ForecastWeekView;
import vn.pnj.meal.api.close.CloseViews.ReconciliationView;
import vn.pnj.meal.api.close.DayCloseService.FinalizeResult;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.common.Xlsx;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.access.Action;
import vn.pnj.meal.rules.access.ListScope;
import vn.pnj.meal.rules.close.Adjustments;

/**
 * /admin/reconciliation (plan 4.1): reconciliation for dept admins (their units, view only) and the
 * HC admin (the factory), finalization (BR-43), the caterer's files (BR-44) and the adjustments
 * after finalization (BR-46, HC admin only).
 */
@RestController
public class ReconciliationController {
  private final ReconciliationService reconciliation;
  private final DayCloseService dayClose;
  private final AdjustmentService adjustments;
  private final ClockService clock;

  public ReconciliationController(
      ReconciliationService reconciliation,
      DayCloseService dayClose,
      AdjustmentService adjustments,
      ClockService clock) {
    this.reconciliation = reconciliation;
    this.dayClose = dayClose;
    this.adjustments = adjustments;
    this.clock = clock;
  }

  private LocalDate dayOrToday(String date) {
    return date == null ? VnTime.vnDateOf(clock.now()) : date(date, "date");
  }

  @RequirePermission(action = Action.RECONCILIATION_VIEW, scope = ScopeType.LIST)
  @GetMapping("/admin/reconciliation")
  ReconciliationView view(
      @RequestParam(required = false) String date,
      @RequestParam(required = false) @Size(min = 1) String orgUnitId,
      ListScope scope,
      RequestPrincipal p) {
    return reconciliation.view(scope, p.factoryId(), dayOrToday(date), orgUnitId);
  }

  public record DateBody(@NotNull String date) {}

  /** BR-43: "Chốt suất". */
  @RequirePermission(action = Action.DAY_FINALIZE, scope = ScopeType.FACTORY)
  @PostMapping("/admin/reconciliation/finalize")
  FinalizeResult closeDay(@Valid @RequestBody DateBody body, RequestPrincipal p) {
    return dayClose.finalizeDay(p.employeeId(), p.factoryId(), date(body.date(), "date"));
  }

  /** BR-44: the final file of a finalized day, with its box-label sheet. */
  @RequirePermission(action = Action.EXPORT_FINAL_AND_FORECAST, scope = ScopeType.FACTORY)
  @GetMapping("/admin/reconciliation/export")
  ResponseEntity<byte[]> exportFinal(@RequestParam String date, RequestPrincipal p) {
    LocalDate day = date(date, "date");
    return Xlsx.download(reconciliation.finalFile(p.factoryId(), day), "chot-so-" + day + ".xlsx");
  }

  private static LocalDate monday(String weekStart) {
    LocalDate day = date(weekStart, "weekStart");
    if (day.getDayOfWeek() != DayOfWeek.MONDAY) {
      throw ApiException.invalid(
          "weekStart must be a Monday", List.of(Map.of("path", "weekStart", "message", "monday")));
    }
    return day;
  }

  /** BR-07: the weekly forecast tab. */
  @RequirePermission(action = Action.EXPORT_FINAL_AND_FORECAST, scope = ScopeType.FACTORY)
  @GetMapping("/admin/reconciliation/forecast")
  ForecastWeekView forecastWeek(@RequestParam String weekStart, RequestPrincipal p) {
    return reconciliation.forecastWeek(p.factoryId(), monday(weekStart));
  }

  /** BR-07 / BR-44: the weekly forecast file, one sheet per serving day. */
  @RequirePermission(action = Action.EXPORT_FINAL_AND_FORECAST, scope = ScopeType.FACTORY)
  @GetMapping("/admin/reconciliation/forecast/export")
  ResponseEntity<byte[]> exportForecast(@RequestParam String weekStart, RequestPrincipal p) {
    LocalDate monday = monday(weekStart);
    return Xlsx.download(
        reconciliation.forecastFile(p.factoryId(), monday), "du-tru-tuan-" + monday + ".xlsx");
  }

  // --- adjustments (BR-46) -----------------------------------------------------------------------

  @RequirePermission(action = Action.ADJUSTMENT_MANAGE, scope = ScopeType.FACTORY)
  @GetMapping("/admin/adjustments")
  AdjustmentsView adjustments(@RequestParam(required = false) String date, RequestPrincipal p) {
    return adjustments.list(p.factoryId(), dayOrToday(date));
  }

  @RequirePermission(
      action = Action.ADJUSTMENT_MANAGE,
      scope = ScopeType.EMPLOYEE_PARAM,
      param = "code")
  @GetMapping("/admin/adjustments/employees/{code}")
  EmployeeDayView employeeDay(
      @PathVariable String code, @RequestParam String date, RequestPrincipal p) {
    return adjustments.employeeDay(p.factoryId(), code, date(date, "date"));
  }

  /**
   * @param delta positive adds meals, negative removes them; read as a number so 1.5 is reported
   *     per line (DELTA_NOT_INTEGER)
   */
  public record AdjustmentLineBody(
      @NotNull @Size(min = 1, max = 64) String employeeCode,
      @NotNull @Size(min = 1, max = 64) String menuItemId,
      @NotNull @DecimalMin("-1000") @DecimalMax("1000") Double delta) {}

  /** BR-46: the reason is required (checked with the lines, so every problem shows at once). */
  public record AdjustmentBody(
      @NotNull String date,
      @Size(max = 500) String reason,
      boolean catererConfirmed,
      @NotNull @Size(max = 200) List<@NotNull @Valid AdjustmentLineBody> lines) {}

  @RequirePermission(action = Action.ADJUSTMENT_MANAGE, scope = ScopeType.FACTORY)
  @PostMapping("/admin/adjustments")
  @ResponseStatus(HttpStatus.CREATED)
  AdjustmentCreated createAdjustment(@Valid @RequestBody AdjustmentBody body, RequestPrincipal p) {
    return adjustments.create(
        p.employeeId(),
        p.factoryId(),
        date(body.date(), "date"),
        body.lines().stream()
            .map(l -> new Adjustments.Line(l.employeeCode(), l.menuItemId(), l.delta()))
            .toList(),
        body.reason(),
        body.catererConfirmed());
  }

  /** BR-46: the supplementary file of one adjustment, only the changed cells. */
  @RequirePermission(action = Action.ADJUSTMENT_MANAGE, scope = ScopeType.FACTORY)
  @GetMapping("/admin/adjustments/{id}/export")
  ResponseEntity<byte[]> exportAdjustment(@PathVariable String id, RequestPrincipal p) {
    AdjustmentService.AdjustmentFile file = adjustments.file(p.factoryId(), id);
    return Xlsx.download(file.body(), "dieu-chinh-" + file.date() + "-" + file.number() + ".xlsx");
  }
}
