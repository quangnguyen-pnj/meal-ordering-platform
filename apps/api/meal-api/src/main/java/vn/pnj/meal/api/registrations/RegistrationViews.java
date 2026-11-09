package vn.pnj.meal.api.registrations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import vn.pnj.meal.api.attendance.AttendanceService;
import vn.pnj.meal.api.dishes.DishTypeLabel;
import vn.pnj.meal.rules.attendance.AttendanceRules.PresenceState;
import vn.pnj.meal.rules.attendance.DayCases.CaseType;
import vn.pnj.meal.rules.registration.RegistrationImport.RegistrationImportError;
import vn.pnj.meal.rules.registration.RegistrationRules.AdminDayWindow;
import vn.pnj.meal.rules.registration.RegistrationRules.EmployeeDayWindow;

/** Response bodies of the registration routes; the shapes the web reads (packages/shared). */
public final class RegistrationViews {
  private RegistrationViews() {}

  /**
   * BR-14: the HC admin replaced the dish this employee had chosen after closeAt, and the meal
   * moved here. Shown in red on the dish (product owner, 2026-10-23).
   */
  public record ReplacedFrom(String from) {}

  /**
   * @param quantity current active quantity (0 = not registered)
   * @param forecast quantity in the forecast snapshot; 0 when taken but had none
   */
  public record RegistrationLineView(
      String menuItemId,
      String displayName,
      DishTypeLabel type,
      int quantity,
      int forecast,
      @Nullable ReplacedFrom replacedAfterClose) {}

  public record NeedsReselect(String displayName, int quantity) {}

  /**
   * @param lockAt when the day locks for employees (day.lockTime, BR-24)
   * @param needsReselect BR-14: meals on a removed dish, waiting to be reselected before the lock
   * @param desserts display-only (BR-11)
   * @param forecastTotal null until the forecast snapshot exists (BR-07)
   * @param changed BR-23: the day differs from the forecast
   * @param extra BR-25: an admin added meals after the lock
   */
  public record MyDayView(
      LocalDate date,
      EmployeeDayWindow window,
      Instant lockAt,
      List<RegistrationLineView> items,
      List<NeedsReselect> needsReselect,
      List<String> desserts,
      int actualTotal,
      @Nullable Integer forecastTotal,
      boolean changed,
      boolean extra) {}

  /** The employee's week on /week: UNASSIGNED, NOT_PUBLISHED or PUBLISHED. */
  @Schema(oneOf = {WeekUnassigned.class, WeekNotPublished.class, WeekPublished.class})
  public sealed interface MyWeekView permits WeekUnassigned, WeekNotPublished, WeekPublished {
    String state();

    LocalDate weekStart();

    /** The Monday of the current week by server time, for the "this week" label. */
    LocalDate currentWeekStart();
  }

  /** BR-60: the employee has no valid meal unit. */
  public record WeekUnassigned(
      @Schema(allowableValues = "UNASSIGNED") String state,
      LocalDate weekStart,
      LocalDate currentWeekStart)
      implements MyWeekView {
    WeekUnassigned(LocalDate weekStart, LocalDate currentWeekStart) {
      this("UNASSIGNED", weekStart, currentWeekStart);
    }
  }

  /** BR-02: the canteen's menu is not published yet. */
  public record WeekNotPublished(
      @Schema(allowableValues = "NOT_PUBLISHED") String state,
      LocalDate weekStart,
      LocalDate currentWeekStart,
      String canteenName)
      implements MyWeekView {
    WeekNotPublished(LocalDate weekStart, LocalDate currentWeekStart, String canteenName) {
      this("NOT_PUBLISHED", weekStart, currentWeekStart, canteenName);
    }
  }

  /**
   * @param forecastTaken BR-07: the forecast snapshot exists; changes are now differences from it
   * @param noForecast BR-04: the menu was published inside its own week, so the forecast is empty
   *     and every meal counts as actual (product owner, 2026-11-03)
   */
  public record WeekPublished(
      @Schema(allowableValues = "PUBLISHED") String state,
      LocalDate weekStart,
      LocalDate currentWeekStart,
      String canteenName,
      Instant closeAt,
      boolean forecastTaken,
      boolean noForecast,
      List<MyDayView> days,
      int totalMeals)
      implements MyWeekView {
    WeekPublished(
        LocalDate weekStart,
        LocalDate currentWeekStart,
        String canteenName,
        Instant closeAt,
        boolean forecastTaken,
        boolean noForecast,
        List<MyDayView> days,
        int totalMeals) {
      this(
          "PUBLISHED",
          weekStart,
          currentWeekStart,
          canteenName,
          closeAt,
          forecastTaken,
          noForecast,
          days,
          totalMeals);
    }
  }

  /** One day on /today: UNASSIGNED, NO_MENU or DAY. */
  @Schema(oneOf = {DayUnassigned.class, DayNoMenu.class, DayOpen.class})
  public sealed interface MyDayRegistrationView permits DayUnassigned, DayNoMenu, DayOpen {
    String state();

    LocalDate date();
  }

  public record DayUnassigned(@Schema(allowableValues = "UNASSIGNED") String state, LocalDate date)
      implements MyDayRegistrationView {
    DayUnassigned(LocalDate date) {
      this("UNASSIGNED", date);
    }
  }

  public record DayNoMenu(
      @Schema(allowableValues = "NO_MENU") String state, LocalDate date, String canteenName)
      implements MyDayRegistrationView {
    DayNoMenu(LocalDate date, String canteenName) {
      this("NO_MENU", date, canteenName);
    }
  }

  /**
   * @param now server time (ClockService), so the countdown to the lock follows fake time too
   * @param attendance BR-30 to BR-35 on day D itself; null on other days
   */
  public record DayOpen(
      @Schema(allowableValues = "DAY") String state,
      LocalDate date,
      String canteenName,
      Instant closeAt,
      boolean forecastTaken,
      MyDayView day,
      Instant now,
      @Nullable MyAttendanceView attendance)
      implements MyDayRegistrationView {
    DayOpen(
        LocalDate date,
        String canteenName,
        Instant closeAt,
        boolean forecastTaken,
        MyDayView day,
        Instant now,
        MyAttendanceView attendance) {
      this("DAY", date, canteenName, closeAt, forecastTaken, day, now, attendance);
    }
  }

  public record SaveResult<T>(T view, List<LocalDate> changedDays) {}

  public record HistoryMeal(String displayName, int forecast, int actual, int cancelled) {}

  /**
   * @param forecast null when no forecast was taken for that week
   * @param status REGISTERED, NOT_REGISTERED or CANCELLED
   */
  public record HistoryDayView(
      LocalDate date,
      @Nullable Integer forecast,
      int actual,
      List<HistoryMeal> meals,
      @Schema(allowableValues = {"REGISTERED", "NOT_REGISTERED", "CANCELLED"}) String status) {}

  public record HistoryView(LocalDate from, LocalDate to, List<HistoryDayView> items) {}

  public record TeamMeal(String displayName, int quantity) {}

  /** BR-27: only name, dish, quantity and eating / not eating; never a reason. */
  public record TeamMemberView(String key, String name, List<TeamMeal> meals, boolean eating) {}

  public record TeamDayView(
      LocalDate date, String unitId, String unitName, List<TeamMemberView> members) {}

  /**
   * @param employeeCount employees below this node who can register (BR-60, BR-63)
   */
  public record UnitOption(
      String id, String name, String pathName, int level, boolean isMealUnit, int employeeCount) {}

  public record Units(
      List<UnitOption> items, LocalDate currentWeekStart, LocalDate upcomingWeekStart) {}

  public record ProgressUnit(
      String mealUnitId,
      String name,
      String pathName,
      @Nullable String canteenName,
      boolean published,
      int headcount,
      int registered,
      int notRegistered,
      int meals) {}

  public record ProgressView(LocalDate weekStart, List<ProgressUnit> units) {}

  public record NotRegisteredItem(
      String employeeCode, String name, String orgPath, String mealUnitName) {}

  public record NotRegisteredView(
      LocalDate weekStart, @Nullable LocalDate date, int total, List<NotRegisteredItem> items) {}

  /**
   * @param forecast null until the forecast snapshot exists
   * @param blocked BR-36: the employee has left on or before this day
   */
  public record GridCellView(
      Map<String, Integer> quantities,
      @Nullable Map<String, Integer> forecast,
      boolean extra,
      int needsReselect,
      boolean blocked) {}

  public record GridItem(String id, String displayName, DishTypeLabel type) {}

  public record GridDay(
      LocalDate date, AdminDayWindow window, Instant lockAt, List<GridItem> items) {}

  public record GridRow(
      String employeeCode, String name, String orgPath, Map<LocalDate, GridCellView> cells) {}

  public record OrgUnitRef(String id, String name, String pathName) {}

  public record CanteenRef(String id, String name) {}

  /** Employees below the node left out of this grid. */
  public record GridSkipped(int otherCanteen, int cannotRegister) {}

  /**
   * @param state PUBLISHED, NOT_PUBLISHED or NO_EMPLOYEES
   */
  public record GridView(
      @Schema(allowableValues = {"PUBLISHED", "NOT_PUBLISHED", "NO_EMPLOYEES"}) String state,
      OrgUnitRef orgUnit,
      LocalDate weekStart,
      List<CanteenRef> canteens,
      @Nullable String canteenId,
      @Nullable String canteenName,
      @Nullable Instant closeAt,
      boolean forecastTaken,
      List<GridDay> days,
      List<GridRow> rows,
      GridSkipped skipped) {}

  /**
   * @param days days written (the import saves several at once)
   */
  public record GridSaveResult(int changedEmployees, int meals, int days) {}

  public record ExtraResult(String employeeCode, LocalDate date, int before, int after) {}

  /**
   * @param status PREVIEW, APPLIED or REJECTED
   * @param changedDays employee-days the file changes
   */
  public record ImportView(
      @Schema(allowableValues = {"PREVIEW", "APPLIED", "REJECTED"}) String status,
      int rowCount,
      List<RegistrationImportError> errors,
      int changedDays,
      int changedEmployees) {}

  // --- /admin/today (P3: BR-24, BR-30 to BR-35, BR-41) ------------------------------------------

  /**
   * One meal of an employee's day: the current quantity and the forecast quantity.
   *
   * @param onMenu false for a forecast meal whose dish is no longer on the menu
   */
  public record TodayLine(
      String menuItemId, String displayName, int quantity, int forecast, boolean onMenu) {}

  /**
   * @param checkedInInTime BR-30: a check-in no later than the cutoff
   * @param confirmedInDmp BR-30 (v1.3): a manager confirmed presence in DMP
   * @param confirmReason BR-33: optional, admins only (never on /team, BR-27)
   */
  public record TodayPresence(
      PresenceState state,
      @Nullable Instant firstCheckInAt,
      boolean checkedInInTime,
      boolean confirmedInDmp,
      @Nullable String confirmedByName,
      @Nullable Instant confirmedAt,
      @Nullable String confirmReason) {}

  /**
   * @param forecastTotal null while the forecast is not taken (BR-07)
   * @param mealsCount false when presence cancels the meals (BR-31)
   * @param cases BR-41 cases to review
   */
  public record TodayRow(
      String employeeCode,
      String name,
      String orgPath,
      @Nullable String canteenId,
      List<TodayLine> lines,
      @Nullable Integer forecastTotal,
      int actualTotal,
      boolean mealsCount,
      TodayPresence presence,
      AttendanceService.@Nullable LeaveFlag leave,
      List<CaseType> cases) {}

  public record TodayMenu(String canteenId, String canteenName, List<GridItem> items) {}

  /**
   * @param countedMeals meals that count once presence is applied (BR-31)
   */
  public record TodaySummary(
      int employees,
      int withMeals,
      int present,
      int provisionallyCancelled,
      int forecastMeals,
      int actualMeals,
      int countedMeals,
      int cases) {}

  /**
   * /admin/today for one serving day.
   *
   * @param window BR-21 / BR-24: ON_BEHALF before the lock, EXTRA (edits with a reason) until
   *     finalization, CLOSED after
   * @param canConfirmPresence BR-33: day D until finalization
   */
  public record AdminTodayView(
      LocalDate date,
      Instant now,
      AdminDayWindow window,
      Instant lockAt,
      boolean canConfirmPresence,
      AttendanceService.AttendanceStatus attendance,
      TodaySummary summary,
      List<TodayMenu> menus,
      List<TodayRow> rows) {}

  public record TodayEditResult(
      String employeeCode,
      LocalDate date,
      Map<String, Integer> before,
      Map<String, Integer> after) {}

  /**
   * The employee's own attendance on /today (BR-30 to BR-35). No reason and no admin name: the
   * employee only sees what happened.
   */
  public record MyAttendanceView(
      PresenceState state,
      @Nullable Instant firstCheckInAt,
      boolean checkedInInTime,
      boolean confirmedInDmp,
      boolean confirmedByAdmin,
      Instant cutoffAt,
      AttendanceService.@Nullable LeaveFlag leave) {}
}
