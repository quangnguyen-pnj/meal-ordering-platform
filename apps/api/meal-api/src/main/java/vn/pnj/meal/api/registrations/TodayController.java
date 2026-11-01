package vn.pnj.meal.api.registrations;

import static vn.pnj.meal.api.menu.MenuController.date;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.pnj.meal.api.access.RequestPrincipal;
import vn.pnj.meal.api.access.RequirePermission;
import vn.pnj.meal.api.access.ScopeType;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.registrations.RegistrationViews.AdminTodayView;
import vn.pnj.meal.api.registrations.RegistrationViews.TodayEditResult;
import vn.pnj.meal.api.registrations.RegistrationViews.TodayPresence;
import vn.pnj.meal.api.registrations.RegistrationsController.LineBody;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.access.Action;
import vn.pnj.meal.rules.access.ListScope;

/**
 * /admin/today (plan 4.1): day D for dept admins (their units) and HC admins (the factory). The
 * matrix row "Confirm presence; edit from 08:00 to finalization" is one permission, {@link
 * Action#PRESENCE_CONFIRM}, which also covers the list and its exceptions tab.
 */
@RestController
public class TodayController {
  private final AdminTodayService today;
  private final ClockService clock;

  public TodayController(AdminTodayService today, ClockService clock) {
    this.today = today;
    this.clock = clock;
  }

  @RequirePermission(action = Action.PRESENCE_CONFIRM, scope = ScopeType.LIST)
  @GetMapping("/admin/today")
  AdminTodayView view(
      @RequestParam(required = false) String date,
      @RequestParam(required = false) @Size(min = 1) String orgUnitId,
      ListScope scope,
      RequestPrincipal p) {
    LocalDate day = date == null ? VnTime.vnDateOf(clock.now()) : date(date, "date");
    return today.view(scope, p.factoryId(), day, orgUnitId);
  }

  /** BR-33: the reason is optional (v1.3). */
  public record PresenceBody(@NotNull String date, @Size(max = 500) String reason) {}

  @RequirePermission(
      action = Action.PRESENCE_CONFIRM,
      scope = ScopeType.EMPLOYEE_PARAM,
      param = "code")
  @PostMapping("/admin/today/employees/{code}/presence")
  TodayPresence confirmPresence(
      @PathVariable String code, @Valid @RequestBody PresenceBody body, RequestPrincipal p) {
    return today.confirmPresence(
        p.employeeId(), p.factoryId(), code, date(body.date(), "date"), body.reason());
  }

  /** BR-24: a day's complete selection after the lock, with a required reason. */
  public record MealsBody(
      @NotNull String date,
      @NotNull @Size(max = 50) List<@NotNull @Valid LineBody> lines,
      @NotNull @Size(min = 1, max = 500) String reason) {
    public MealsBody {
      reason = reason == null ? null : reason.strip();
    }
  }

  @RequirePermission(
      action = Action.PRESENCE_CONFIRM,
      scope = ScopeType.EMPLOYEE_PARAM,
      param = "code")
  @PutMapping("/admin/today/employees/{code}/meals")
  TodayEditResult editMeals(
      @PathVariable String code, @Valid @RequestBody MealsBody body, RequestPrincipal p) {
    return today.editMeals(
        p.employeeId(),
        p.factoryId(),
        code,
        date(body.date(), "date"),
        LineBody.lines(body.lines()),
        body.reason());
  }
}
