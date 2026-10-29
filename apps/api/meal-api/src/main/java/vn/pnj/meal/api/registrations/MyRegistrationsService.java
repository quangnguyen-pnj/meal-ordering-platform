package vn.pnj.meal.api.registrations;

import static vn.pnj.meal.api.registrations.RegistrationStore.activeQuantities;
import static vn.pnj.meal.api.registrations.RegistrationStore.assertMonday;
import static vn.pnj.meal.api.registrations.RegistrationStore.dayLocked;
import static vn.pnj.meal.api.registrations.RegistrationStore.sameQuantities;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.pnj.meal.api.access.RequestPrincipal;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.registrations.RegistrationStore.Employee;
import vn.pnj.meal.api.registrations.RegistrationStore.LoadedDay;
import vn.pnj.meal.api.registrations.RegistrationStore.LoadedWeek;
import vn.pnj.meal.api.registrations.RegistrationStore.RegRow;
import vn.pnj.meal.api.registrations.RegistrationViews.DayNoMenu;
import vn.pnj.meal.api.registrations.RegistrationViews.DayOpen;
import vn.pnj.meal.api.registrations.RegistrationViews.DayUnassigned;
import vn.pnj.meal.api.registrations.RegistrationViews.HistoryDayView;
import vn.pnj.meal.api.registrations.RegistrationViews.HistoryMeal;
import vn.pnj.meal.api.registrations.RegistrationViews.HistoryView;
import vn.pnj.meal.api.registrations.RegistrationViews.MyDayRegistrationView;
import vn.pnj.meal.api.registrations.RegistrationViews.MyDayView;
import vn.pnj.meal.api.registrations.RegistrationViews.MyWeekView;
import vn.pnj.meal.api.registrations.RegistrationViews.SaveResult;
import vn.pnj.meal.api.registrations.RegistrationViews.TeamDayView;
import vn.pnj.meal.api.registrations.RegistrationViews.TeamMeal;
import vn.pnj.meal.api.registrations.RegistrationViews.TeamMemberView;
import vn.pnj.meal.api.registrations.RegistrationViews.WeekNotPublished;
import vn.pnj.meal.api.registrations.RegistrationViews.WeekPublished;
import vn.pnj.meal.api.registrations.RegistrationViews.WeekUnassigned;
import vn.pnj.meal.api.settings.SettingKey;
import vn.pnj.meal.api.settings.SettingsService;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.deadline.Deadlines;
import vn.pnj.meal.rules.menu.MenuRules;
import vn.pnj.meal.rules.registration.RegistrationRules;
import vn.pnj.meal.rules.registration.RegistrationRules.EmployeeDayWindow;
import vn.pnj.meal.rules.registration.RegistrationRules.Line;

/**
 * An employee's own registration (BR-20 to BR-24), the /today part of it, history, and the
 * colleagues view (BR-27).
 */
@Service
public class MyRegistrationsService {
  private static final int MAX_HISTORY_DAYS = 92;

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final RegistrationStore store;
  private final ForecastService forecast;
  private final SettingsService settings;
  private final AuditService audit;
  private final ClockService clock;

  public MyRegistrationsService(
      JdbcClient jdbc,
      TransactionTemplate tx,
      RegistrationStore store,
      ForecastService forecast,
      SettingsService settings,
      AuditService audit,
      ClockService clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.store = store;
    this.forecast = forecast;
    this.settings = settings;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * J1: the week currently open for registration is preselected: the first of the next two weeks
   * whose menu is published and still before closeAt, else next week.
   */
  public LocalDate defaultWeekStart(RequestPrincipal p) {
    Instant now = clock.now();
    LocalDate next = Deadlines.upcomingWeekStart(now);
    Employee me = store.employee(p.factoryId(), p.employeeId());
    String canteenId = me.assignment().canRegister() ? me.assignment().canteenId() : null;
    if (canteenId == null) {
      return next;
    }
    for (LocalDate weekStart : List.of(next, next.plusDays(7))) {
      LoadedWeek week = store.loadWeek(canteenId, weekStart);
      if (week != null && week.published() && now.isBefore(week.closeAt())) {
        return weekStart;
      }
    }
    return next;
  }

  public MyWeekView week(RequestPrincipal p, LocalDate weekStart) {
    assertMonday(weekStart);
    forecast.catchUp(p.factoryId());
    Employee me = store.employee(p.factoryId(), p.employeeId());
    if (!me.assignment().canRegister() || me.assignment().canteenId() == null) {
      return new WeekUnassigned(weekStart);
    }
    LoadedWeek week = store.loadWeek(me.assignment().canteenId(), weekStart);
    if (week == null || !week.published()) {
      String canteenName =
          jdbc.sql("SELECT name FROM \"Canteen\" WHERE id = :id")
              .param("id", me.assignment().canteenId())
              .query(String.class)
              .single();
      return new WeekNotPublished(weekStart, canteenName);
    }
    List<LoadedDay> serving = week.servingDays();
    List<RegRow> rows =
        store.rowsOf(List.of(me.id()), serving.stream().map(LoadedDay::date).toList());
    Map<LocalDate, Map<String, Integer>> mine =
        week.closedAt() == null
            ? null
            : store.forecastOf(week.id(), List.of(me.id())).getOrDefault(me.id(), Map.of());
    LocalTime lockTime = settings.getTime(p.factoryId(), SettingKey.DAY_LOCK_TIME);
    Instant now = clock.now();
    List<MyDayView> days =
        serving.stream()
            .map(
                day ->
                    RegistrationStore.dayView(
                        week,
                        day,
                        rows.stream().filter(r -> r.date().equals(day.date())).toList(),
                        mine == null ? null : mine.getOrDefault(day.date(), Map.of()),
                        now,
                        lockTime,
                        me.endDate()))
            .toList();
    return new WeekPublished(
        weekStart,
        week.canteenName(),
        week.closeAt(),
        week.closedAt() != null,
        days,
        days.stream().mapToInt(MyDayView::actualTotal).sum());
  }

  /** One day of a save request. */
  public record DayInput(LocalDate date, List<Line> lines) {}

  public SaveResult<MyWeekView> saveWeek(
      RequestPrincipal p, LocalDate weekStart, List<DayInput> days) {
    assertMonday(weekStart);
    LocalDate weekEnd = weekStart.plusDays(6);
    for (DayInput d : days) {
      if (d.date().isBefore(weekStart) || d.date().isAfter(weekEnd)) {
        throw ApiException.invalid(
            "Day outside the week",
            List.of(Map.of("path", "days", "message", d.date().toString())));
      }
    }
    List<LocalDate> changed = save(p, weekStart, days);
    return new SaveResult<>(week(p, weekStart), changed);
  }

  /** /today: one day (default today, Vietnam time). */
  public MyDayRegistrationView day(RequestPrincipal p, LocalDate date) {
    LocalDate target = date != null ? date : VnTime.vnDateOf(clock.now());
    return switch (week(p, VnTime.mondayOf(target))) {
      case WeekUnassigned u -> new DayUnassigned(target);
      case WeekNotPublished w -> new DayNoMenu(target, w.canteenName());
      case WeekPublished w ->
          w.days().stream()
              .filter(d -> d.date().equals(target))
              .findFirst()
              .<MyDayRegistrationView>map(
                  d ->
                      new DayOpen(
                          target, w.canteenName(), w.closeAt(), w.forecastTaken(), d, clock.now()))
              .orElseGet(() -> new DayNoMenu(target, w.canteenName()));
    };
  }

  public SaveResult<MyDayRegistrationView> saveDay(
      RequestPrincipal p, LocalDate date, List<Line> lines) {
    List<LocalDate> changed = save(p, VnTime.mondayOf(date), List.of(new DayInput(date, lines)));
    return new SaveResult<>(day(p, date), changed);
  }

  /**
   * BR-20 to BR-24 on the server: validates every day, then applies all of them in one transaction
   * or none. Changes after closeAt are audited (BR-70); a changed day that has locked rejects the
   * whole save (BR-24).
   */
  private List<LocalDate> save(RequestPrincipal p, LocalDate weekStart, List<DayInput> days) {
    List<LocalDate> dates = days.stream().map(DayInput::date).toList();
    if (new HashSet<>(dates).size() != dates.size()) {
      throw ApiException.invalid(
          "Each day may appear once", List.of(Map.of("path", "days", "message", "duplicate")));
    }
    Employee me = store.employee(p.factoryId(), p.employeeId());
    if (!me.assignment().canRegister() || me.assignment().canteenId() == null) {
      throw ApiException.invalidState("Employee has no valid meal unit (BR-60)", null);
    }
    // Snapshot first if closeAt has passed, so the forecast never contains a later change.
    forecast.closeDueWeeks(p.factoryId());
    LoadedWeek week = store.loadWeek(me.assignment().canteenId(), weekStart);
    if (week == null || !week.published()) {
      throw ApiException.invalidState("Menu is not published (BR-02)", null);
    }
    validateDays(week, days);

    LocalTime lockTime = settings.getTime(p.factoryId(), SettingKey.DAY_LOCK_TIME);
    List<LocalDate> changed = new ArrayList<>();
    tx.executeWithoutResult(
        status -> {
          boolean forecastTaken = store.lockWeek(week.id());
          Instant now = clock.now();
          List<RegRow> rows = store.rowsOf(List.of(me.id()), dates);
          for (DayInput d : days) {
            List<RegRow> existing = rows.stream().filter(r -> r.date().equals(d.date())).toList();
            Map<String, Integer> before = activeQuantities(existing);
            Map<String, Integer> after = RegistrationRules.quantitiesOf(d.lines());
            if (sameQuantities(before, after)) {
              continue;
            }
            EmployeeDayWindow window =
                RegistrationRules.employeeDayWindow(
                    now, d.date(), week.closeAt(), lockTime, forecastTaken);
            if (window == EmployeeDayWindow.LOCKED || me.leftBy(d.date())) {
              throw dayLocked(d.date());
            }
            store.applyDay(me.id(), d.date(), after, existing, "SELF", now);
            changed.add(d.date());
            if (window == EmployeeDayWindow.AFTER_CLOSE) {
              // BR-23 / BR-70: every change after closeAt is recorded against the forecast.
              Map<String, Object> afterAudit = new LinkedHashMap<>();
              afterAudit.put("quantities", after);
              afterAudit.put("menuWeekId", week.id());
              audit.log(
                  AuditService.Entry.of(
                          me.id(),
                          "registration.changedAfterClose",
                          "RegistrationDay",
                          me.employeeCode() + ":" + d.date())
                      .withBefore(Map.of("quantities", before))
                      .withAfter(afterAudit));
            }
          }
        });
    return List.copyOf(changed);
  }

  private static void validateDays(LoadedWeek week, List<DayInput> days) {
    List<Map<String, String>> errors = new ArrayList<>();
    for (DayInput d : days) {
      LoadedDay day = week.day(d.date());
      if (day == null || !day.isServing()) {
        errors.add(error(d.date(), "NOT_SERVING", null));
        continue;
      }
      RegistrationRules.validateDaySelection(d.lines(), day.itemIds())
          .forEach(e -> errors.add(error(d.date(), e.code().name(), e.menuItemId())));
    }
    if (!errors.isEmpty()) {
      throw ApiException.invalid("Invalid selection (BR-20)", errors);
    }
  }

  private static Map<String, String> error(LocalDate date, String code, String menuItemId) {
    Map<String, String> error = new LinkedHashMap<>();
    error.put("date", date.toString());
    error.put("code", code);
    if (menuItemId != null) {
      error.put("menuItemId", menuItemId);
    }
    return error;
  }

  private record HistoryLine(
      LocalDate date, int quantity, String status, String dishName, String typeName) {}

  /** /history: past days with forecast, actual and status, newest first. */
  public HistoryView history(RequestPrincipal p, LocalDate from, LocalDate to) {
    LocalDate today = VnTime.vnDateOf(clock.now());
    LocalDate end = to != null ? to : today;
    LocalDate start = from != null ? from : end.minusDays(27);
    if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) > MAX_HISTORY_DAYS) {
      throw ApiException.invalid(
          "Invalid range",
          List.of(Map.of("path", "from", "message", "max " + MAX_HISTORY_DAYS + " days")));
    }
    forecast.cancelUnselectedAtLock(p.factoryId());
    List<HistoryLine> rows =
        jdbc.sql(
                """
                SELECT r.date, r.quantity, r.status::text, d.name AS "dishName",
                       t.name AS "typeName"
                FROM "Registration" r
                JOIN "MenuItem" i ON i.id = r."menuItemId"
                JOIN "Dish" d ON d.id = i."dishId"
                JOIN "DishType" t ON t.id = d."typeId"
                WHERE r."employeeId" = :employeeId AND r.date BETWEEN :start AND :end
                ORDER BY r.id
                """)
            .param("employeeId", p.employeeId())
            .param("start", start)
            .param("end", end)
            .query(HistoryLine.class)
            .list();
    List<HistoryLine> lines =
        jdbc.sql(
                """
                SELECT f.date, f.quantity, 'ACTIVE' AS status, d.name AS "dishName",
                       t.name AS "typeName"
                FROM "ForecastLine" f
                JOIN "MenuItem" i ON i.id = f."menuItemId"
                JOIN "Dish" d ON d.id = i."dishId"
                JOIN "DishType" t ON t.id = d."typeId"
                WHERE f."employeeId" = :employeeId AND f.date BETWEEN :start AND :end
                ORDER BY f.id
                """)
            .param("employeeId", p.employeeId())
            .param("start", start)
            .param("end", end)
            .query(HistoryLine.class)
            .list();
    // Weeks with a forecast: a day of such a week without lines had a forecast of 0.
    Set<LocalDate> forecastWeeks =
        new HashSet<>(
            jdbc.sql(
                    """
                    SELECT DISTINCT w."weekStart" FROM "ForecastLine" f
                    JOIN "MenuWeek" w ON w.id = f."menuWeekId"
                    WHERE f."employeeId" = :employeeId AND f.date BETWEEN :start AND :end
                    """)
                .param("employeeId", p.employeeId())
                .param("start", start)
                .param("end", end)
                .query(LocalDate.class)
                .list());

    Map<LocalDate, HistoryEntry> byDate = new LinkedHashMap<>();
    for (HistoryLine l : lines) {
      HistoryEntry e = byDate.computeIfAbsent(l.date(), d -> new HistoryEntry(d, forecastWeeks));
      e.forecast = (e.forecast == null ? 0 : e.forecast) + l.quantity();
      e.meal(MenuRules.mealDisplayName(l.typeName(), l.dishName()))[0] += l.quantity();
    }
    for (HistoryLine r : rows) {
      if (r.quantity() <= 0) {
        continue;
      }
      HistoryEntry e = byDate.computeIfAbsent(r.date(), d -> new HistoryEntry(d, forecastWeeks));
      int[] meal = e.meal(MenuRules.mealDisplayName(r.typeName(), r.dishName()));
      if (r.status().equals("ACTIVE")) {
        e.actual += r.quantity();
        meal[1] += r.quantity();
      } else {
        meal[2] += r.quantity();
      }
    }
    List<HistoryDayView> items =
        byDate.values().stream()
            .map(HistoryEntry::view)
            .sorted(Comparator.comparing(HistoryDayView::date).reversed())
            .toList();
    return new HistoryView(start, end, items);
  }

  /** One day of /history while it is being summed up. */
  private static final class HistoryEntry {
    private final LocalDate date;
    private Integer forecast;
    private int actual;

    /** Display name → forecast, actual, cancelled. */
    private final Map<String, int[]> meals = new LinkedHashMap<>();

    HistoryEntry(LocalDate date, Set<LocalDate> forecastWeeks) {
      this.date = date;
      this.forecast = forecastWeeks.contains(VnTime.mondayOf(date)) ? 0 : null;
    }

    int[] meal(String displayName) {
      return meals.computeIfAbsent(displayName, k -> new int[3]);
    }

    HistoryDayView view() {
      List<HistoryMeal> list =
          meals.entrySet().stream()
              .map(
                  m ->
                      new HistoryMeal(
                          m.getKey(), m.getValue()[0], m.getValue()[1], m.getValue()[2]))
              .toList();
      String status =
          actual > 0
              ? "REGISTERED"
              : list.stream().anyMatch(m -> m.cancelled() > 0) ? "CANCELLED" : "NOT_REGISTERED";
      return new HistoryDayView(date, forecast, actual, list, status);
    }
  }

  private record Unit(String id, String factoryId, String name, String path, boolean isMealUnit) {}

  private record Member(String id, String name) {}

  private record TeamLine(String employeeId, int quantity, String dishName, String typeName) {}

  /**
   * BR-27: colleagues of a meal unit today: name, dish, quantity and eating / not eating only.
   * Never a reason for absence or leave. {@code unitId} defaults to the caller's meal unit.
   */
  public TeamDayView team(RequestPrincipal p, String unitId, String q) {
    String id = unitId != null ? unitId : p.mealUnitId();
    if (id == null) {
      throw ApiException.notFound("No meal unit");
    }
    Unit unit =
        jdbc.sql(
                """
                SELECT id, "factoryId", name, path, "isMealUnit" FROM "OrgUnit" WHERE id = :id
                """)
            .param("id", id)
            .query(Unit.class)
            .optional()
            .filter(u -> u.factoryId().equals(p.factoryId()))
            .orElseThrow(() -> ApiException.notFound("Unit not found"));
    LocalDate date = VnTime.vnDateOf(clock.now());
    StringBuilder where =
        new StringBuilder(
            unit.isMealUnit() ? "e.\"mealUnitId\" = :unitId" : "starts_with(o.path, :path)");
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("unitId", unit.id());
    params.put("path", unit.path());
    if (q != null && !q.isEmpty()) {
      where.append(" AND (e.\"employeeCode\" ILIKE :q OR e.name ILIKE :q)");
      params.put("q", "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
    }
    List<Member> members =
        jdbc.sql(
                """
                SELECT e.id, e.name FROM "Employee" e JOIN "OrgUnit" o ON o.id = e."orgUnitId"
                WHERE e.status = 'ACTIVE' AND %s
                ORDER BY e.name, e."employeeCode"
                LIMIT 500
                """
                    .formatted(where))
            .params(params)
            .query(Member.class)
            .list();
    Map<String, List<TeamMeal>> meals = new LinkedHashMap<>();
    if (!members.isEmpty()) {
      jdbc.sql(
              """
              SELECT r."employeeId", r.quantity, d.name AS "dishName", t.name AS "typeName"
              FROM "Registration" r
              JOIN "MenuItem" i ON i.id = r."menuItemId"
              JOIN "Dish" d ON d.id = i."dishId"
              JOIN "DishType" t ON t.id = d."typeId"
              WHERE r."employeeId" IN (:ids) AND r.date = :date AND r.session = 'LUNCH'
                AND r.status = 'ACTIVE' AND r.quantity > 0
              ORDER BY r.id
              """)
          .param("ids", members.stream().map(Member::id).toList())
          .param("date", date)
          .query(TeamLine.class)
          .list()
          .forEach(
              l ->
                  meals
                      .computeIfAbsent(l.employeeId(), k -> new ArrayList<>())
                      .add(
                          new TeamMeal(
                              MenuRules.mealDisplayName(l.typeName(), l.dishName()),
                              l.quantity())));
    }
    return new TeamDayView(
        date,
        unit.id(),
        unit.name(),
        members.stream()
            .map(
                m -> {
                  List<TeamMeal> mine = meals.getOrDefault(m.id(), List.of());
                  return new TeamMemberView(m.id(), m.name(), mine, !mine.isEmpty());
                })
            .toList());
  }
}
