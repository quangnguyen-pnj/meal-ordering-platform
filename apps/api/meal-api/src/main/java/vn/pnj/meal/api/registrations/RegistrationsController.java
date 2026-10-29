package vn.pnj.meal.api.registrations;

import static vn.pnj.meal.api.menu.MenuController.date;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.pnj.meal.api.access.RequestPrincipal;
import vn.pnj.meal.api.access.RequirePermission;
import vn.pnj.meal.api.access.ScopeType;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.common.JsonBody;
import vn.pnj.meal.api.common.Xlsx;
import vn.pnj.meal.api.employees.EmployeesController.OrgUnitOptions;
import vn.pnj.meal.api.org.OrgController;
import vn.pnj.meal.api.registrations.AdminRegistrationsService.GridRowInput;
import vn.pnj.meal.api.registrations.MyRegistrationsService.DayInput;
import vn.pnj.meal.api.registrations.RegistrationViews.ExtraResult;
import vn.pnj.meal.api.registrations.RegistrationViews.GridSaveResult;
import vn.pnj.meal.api.registrations.RegistrationViews.GridView;
import vn.pnj.meal.api.registrations.RegistrationViews.HistoryView;
import vn.pnj.meal.api.registrations.RegistrationViews.ImportView;
import vn.pnj.meal.api.registrations.RegistrationViews.MyDayRegistrationView;
import vn.pnj.meal.api.registrations.RegistrationViews.MyWeekView;
import vn.pnj.meal.api.registrations.RegistrationViews.NotRegisteredView;
import vn.pnj.meal.api.registrations.RegistrationViews.ProgressView;
import vn.pnj.meal.api.registrations.RegistrationViews.SaveResult;
import vn.pnj.meal.api.registrations.RegistrationViews.TeamDayView;
import vn.pnj.meal.api.registrations.RegistrationViews.Units;
import vn.pnj.meal.rules.access.Action;
import vn.pnj.meal.rules.access.ListScope;
import vn.pnj.meal.rules.deadline.Deadlines;
import vn.pnj.meal.rules.registration.RegistrationRules.Line;

/**
 * Registration (BR-20 to BR-27): /registrations/me (the employee's own meals on /week, /today and
 * /history), /team (colleagues today) and /admin/registrations (dept admins: their units; HC
 * admins: the factory).
 */
@RestController
public class RegistrationsController {
  private static final String BOOL = "true|false";

  private final MyRegistrationsService mine;
  private final AdminRegistrationsService admin;
  private final RegistrationImportService imports;
  private final ClockService clock;

  public RegistrationsController(
      MyRegistrationsService mine,
      AdminRegistrationsService admin,
      RegistrationImportService imports,
      ClockService clock) {
    this.mine = mine;
    this.admin = admin;
    this.imports = imports;
    this.clock = clock;
  }

  private static LocalDate optionalDate(String value, String path) {
    return value == null ? null : date(value, path);
  }

  /**
   * BR-20: one line of a day's selection. Integer and sign are checked per line by the rules; the
   * bound below only keeps values inside the database column, it is not a portion limit.
   */
  public record LineBody(
      @NotNull @Size(min = 1) String menuItemId, @NotNull @DecimalMax("1000000") Double quantity) {
    Line line() {
      return new Line(menuItemId, quantity);
    }

    static List<Line> lines(List<LineBody> body) {
      return body.stream().map(LineBody::line).toList();
    }
  }

  /** A day's complete selection: lines not listed keep quantity 0 (BR-20: no row = no meal). */
  public record DaySelectionBody(@NotNull @Size(max = 50) List<@NotNull @Valid LineBody> lines) {}

  public record WeekDayBody(
      @NotNull String date, @NotNull @Size(max = 50) List<@NotNull @Valid LineBody> lines) {}

  /** /week: save several days at once (BR-22, BR-23). Days not listed are left as they are. */
  public record WeekSaveBody(
      @NotNull String weekStart,
      @NotNull @Size(min = 1, max = 7) List<@NotNull @Valid WeekDayBody> days) {}

  @RequirePermission(action = Action.MENU_REGISTER_OWN, scope = ScopeType.SELF)
  @GetMapping("/registrations/me/week")
  MyWeekView week(@RequestParam(required = false) String weekStart, RequestPrincipal p) {
    LocalDate week = weekStart == null ? mine.defaultWeekStart(p) : date(weekStart, "weekStart");
    return mine.week(p, week);
  }

  @RequirePermission(action = Action.MENU_REGISTER_OWN, scope = ScopeType.SELF)
  @PutMapping("/registrations/me/week")
  SaveResult<MyWeekView> saveWeek(@Valid @RequestBody WeekSaveBody body, RequestPrincipal p) {
    LocalDate weekStart = date(body.weekStart(), "weekStart");
    List<DayInput> days = new ArrayList<>();
    for (int i = 0; i < body.days().size(); i++) {
      WeekDayBody d = body.days().get(i);
      days.add(new DayInput(date(d.date(), "days." + i + ".date"), LineBody.lines(d.lines())));
    }
    return mine.saveWeek(p, weekStart, days);
  }

  @RequirePermission(action = Action.MENU_REGISTER_OWN, scope = ScopeType.SELF)
  @GetMapping("/registrations/me/day")
  MyDayRegistrationView day(@RequestParam(required = false) String date, RequestPrincipal p) {
    return mine.day(p, optionalDate(date, "date"));
  }

  @RequirePermission(action = Action.MENU_REGISTER_OWN, scope = ScopeType.SELF)
  @PutMapping("/registrations/me/days/{date}")
  SaveResult<MyDayRegistrationView> saveDay(
      @PathVariable("date") String dateParam,
      @Valid @RequestBody DaySelectionBody body,
      RequestPrincipal p) {
    return mine.saveDay(p, date(dateParam, "date"), LineBody.lines(body.lines()));
  }

  @RequirePermission(action = Action.MENU_REGISTER_OWN, scope = ScopeType.SELF)
  @GetMapping("/registrations/me/history")
  HistoryView history(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      RequestPrincipal p) {
    return mine.history(p, optionalDate(from, "from"), optionalDate(to, "to"));
  }

  private static String search(String q) {
    return q == null ? null : q.strip();
  }

  /** BR-27: the caller's own meal unit, which is always inside their OWN_UNIT grant. */
  @RequirePermission(action = Action.TEAM_VIEW, scope = ScopeType.LIST)
  @GetMapping("/team")
  TeamDayView team(
      @RequestParam(required = false) @Size(max = 64) String q,
      ListScope scope,
      RequestPrincipal p) {
    return mine.team(p, null, search(q));
  }

  @RequirePermission(
      action = Action.TEAM_VIEW,
      scope = ScopeType.ORG_UNIT_PARAM,
      param = "orgUnitId")
  @GetMapping("/team/units/{orgUnitId}")
  TeamDayView teamUnit(
      @PathVariable String orgUnitId,
      @RequestParam(required = false) @Size(max = 64) String q,
      RequestPrincipal p) {
    return mine.team(p, orgUnitId, search(q));
  }

  @RequirePermission(action = Action.REGISTRATION_ON_BEHALF, scope = ScopeType.LIST)
  @GetMapping("/admin/registrations/units")
  Units units(ListScope scope, RequestPrincipal p) {
    // Screens default to the coming week by server time (fake time in dev and tests).
    return new Units(admin.units(scope, p.factoryId()), Deadlines.upcomingWeekStart(clock.now()));
  }

  @RequirePermission(action = Action.REGISTRATION_ON_BEHALF, scope = ScopeType.LIST)
  @GetMapping("/admin/registrations/progress")
  ProgressView progress(@RequestParam String weekStart, ListScope scope, RequestPrincipal p) {
    return admin.progress(scope, p.factoryId(), date(weekStart, "weekStart"));
  }

  /** BR-21: who has not registered yet. */
  @RequirePermission(action = Action.REGISTRATION_NOT_REGISTERED_LIST, scope = ScopeType.LIST)
  @GetMapping("/admin/registrations/not-registered")
  NotRegisteredView notRegistered(
      @RequestParam String weekStart,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) @Size(min = 1) String orgUnitId,
      ListScope scope,
      RequestPrincipal p) {
    return admin.notRegistered(
        scope, p.factoryId(), date(weekStart, "weekStart"), optionalDate(date, "date"), orgUnitId);
  }

  @RequirePermission(action = Action.REGISTRATION_NOT_REGISTERED_LIST, scope = ScopeType.LIST)
  @GetMapping("/admin/registrations/org-units")
  OrgUnitOptions orgUnits(ListScope scope, RequestPrincipal p) {
    return new OrgUnitOptions(admin.orgUnits(scope, p.factoryId()));
  }

  @RequirePermission(
      action = Action.REGISTRATION_ON_BEHALF,
      scope = ScopeType.ORG_UNIT_PARAM,
      param = "orgUnitId")
  @GetMapping("/admin/registrations/units/{orgUnitId}/grid")
  GridView grid(
      @PathVariable String orgUnitId,
      @RequestParam String weekStart,
      @RequestParam(required = false) @Size(min = 1) String canteenId,
      RequestPrincipal p) {
    return admin.grid(p.factoryId(), orgUnitId, date(weekStart, "weekStart"), canteenId);
  }

  public record GridRowBody(
      @NotNull @Size(min = 1, max = 32) String employeeCode,
      @NotNull @Size(max = 50) List<@NotNull @Valid LineBody> lines) {
    public GridRowBody {
      employeeCode = employeeCode == null ? null : employeeCode.strip();
    }
  }

  /** Bulk-entry grid: one day of one org unit, saved as a whole batch (plan 4.1). */
  public record GridDayBody(
      @NotNull @Size(min = 1) String canteenId,
      @NotNull @Size(min = 1, max = 1000) List<@NotNull @Valid GridRowBody> rows) {}

  @RequirePermission(
      action = Action.REGISTRATION_ON_BEHALF,
      scope = ScopeType.ORG_UNIT_PARAM,
      param = "orgUnitId")
  @PutMapping("/admin/registrations/units/{orgUnitId}/days/{date}")
  GridSaveResult saveGridDay(
      @PathVariable String orgUnitId,
      @PathVariable("date") String dateParam,
      @Valid @RequestBody GridDayBody body,
      RequestPrincipal p) {
    return admin.saveGridDay(
        p.employeeId(),
        p.factoryId(),
        orgUnitId,
        date(dateParam, "date"),
        body.canteenId(),
        body.rows().stream()
            .map(r -> new GridRowInput(r.employeeCode(), LineBody.lines(r.lines())))
            .toList());
  }

  /** The unit's week as an Excel file to fill in (same rows and meals as the grid). */
  @RequirePermission(
      action = Action.REGISTRATION_ON_BEHALF,
      scope = ScopeType.ORG_UNIT_PARAM,
      param = "orgUnitId")
  @GetMapping("/admin/registrations/units/{orgUnitId}/import-template")
  ResponseEntity<byte[]> importTemplate(
      @PathVariable String orgUnitId,
      @RequestParam String weekStart,
      @RequestParam(required = false) @Size(min = 1) String canteenId,
      RequestPrincipal p) {
    LocalDate week = date(weekStart, "weekStart");
    return Xlsx.download(
        imports.template(p.factoryId(), orgUnitId, week, canteenId), "dang-ky-" + week + ".xlsx");
  }

  /** Validates an uploaded week (preview) or, with {@code apply=true}, saves it all at once. */
  @RequirePermission(
      action = Action.REGISTRATION_ON_BEHALF,
      scope = ScopeType.ORG_UNIT_PARAM,
      param = "orgUnitId")
  @PostMapping("/admin/registrations/units/{orgUnitId}/import")
  ImportView importWeek(
      @PathVariable String orgUnitId,
      @RequestParam String weekStart,
      @RequestParam(required = false) @Size(min = 1) String canteenId,
      @RequestParam(required = false) @Pattern(regexp = BOOL) String apply,
      @RequestParam(name = "file", required = false) MultipartFile file,
      RequestPrincipal p) {
    LocalDate week = date(weekStart, "weekStart");
    return imports.importWeek(
        p.employeeId(),
        p.factoryId(),
        orgUnitId,
        week,
        canteenId,
        "true".equals(apply),
        OrgController.bytes(file));
  }

  /** BR-25: an admin adds meals after the lock; flagged "Extra", reason required. */
  public record ExtraBody(
      @NotNull String date,
      @NotNull @Size(min = 1) String menuItemId,
      @NotNull @Positive @DecimalMax("1000000") Double quantity,
      @NotNull @Size(min = 1, max = 500) String reason) {
    public ExtraBody {
      reason = reason == null ? null : reason.strip();
    }
  }

  /** BR-25: extra meals after the lock, with a reason. */
  @RequirePermission(
      action = Action.REGISTRATION_ADD_EXTRA,
      scope = ScopeType.EMPLOYEE_PARAM,
      param = "code")
  @PostMapping("/admin/registrations/employees/{code}/extras")
  ExtraResult addExtra(
      @PathVariable String code, @Valid @RequestBody ExtraBody body, RequestPrincipal p) {
    // A double, so that 1.5 is refused like z.number().int() instead of being truncated.
    if (body.quantity() != Math.rint(body.quantity())) {
      JsonBody.fail("quantity", "Expected integer");
    }
    return admin.addExtra(
        p.employeeId(),
        p.factoryId(),
        code,
        date(body.date(), "date"),
        body.menuItemId(),
        body.quantity().intValue(),
        body.reason());
  }
}
