package vn.pnj.meal.api.registrations;

import static vn.pnj.meal.api.registrations.RegistrationStore.activeQuantities;
import static vn.pnj.meal.api.registrations.RegistrationStore.assertMonday;
import static vn.pnj.meal.api.registrations.RegistrationStore.dayLocked;

import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.employees.EmployeeScopeSql;
import vn.pnj.meal.api.org.OrgDirectory;
import vn.pnj.meal.api.org.OrgSnapshot;
import vn.pnj.meal.api.registrations.RegistrationStore.Applied;
import vn.pnj.meal.api.registrations.RegistrationStore.Employee;
import vn.pnj.meal.api.registrations.RegistrationStore.ExtraAdded;
import vn.pnj.meal.api.registrations.RegistrationStore.LoadedDay;
import vn.pnj.meal.api.registrations.RegistrationStore.LoadedWeek;
import vn.pnj.meal.api.registrations.RegistrationStore.PublishedDay;
import vn.pnj.meal.api.registrations.RegistrationStore.RegRow;
import vn.pnj.meal.api.registrations.RegistrationViews.CanteenRef;
import vn.pnj.meal.api.registrations.RegistrationViews.ExtraResult;
import vn.pnj.meal.api.registrations.RegistrationViews.GridCellView;
import vn.pnj.meal.api.registrations.RegistrationViews.GridDay;
import vn.pnj.meal.api.registrations.RegistrationViews.GridItem;
import vn.pnj.meal.api.registrations.RegistrationViews.GridRow;
import vn.pnj.meal.api.registrations.RegistrationViews.GridSaveResult;
import vn.pnj.meal.api.registrations.RegistrationViews.GridSkipped;
import vn.pnj.meal.api.registrations.RegistrationViews.GridView;
import vn.pnj.meal.api.registrations.RegistrationViews.NotRegisteredItem;
import vn.pnj.meal.api.registrations.RegistrationViews.NotRegisteredView;
import vn.pnj.meal.api.registrations.RegistrationViews.OrgUnitRef;
import vn.pnj.meal.api.registrations.RegistrationViews.ProgressUnit;
import vn.pnj.meal.api.registrations.RegistrationViews.ProgressView;
import vn.pnj.meal.api.registrations.RegistrationViews.UnitOption;
import vn.pnj.meal.api.settings.SettingKey;
import vn.pnj.meal.api.settings.SettingsService;
import vn.pnj.meal.rules.access.ListScope;
import vn.pnj.meal.rules.deadline.Deadlines;
import vn.pnj.meal.rules.registration.RegistrationRules;
import vn.pnj.meal.rules.registration.RegistrationRules.AdminDayWindow;
import vn.pnj.meal.rules.registration.RegistrationRules.Line;

/**
 * Dept and HC admins' side of registration: progress by unit, the not-registered list (BR-21), the
 * bulk-entry grid and paper forms (plan 4.1), and extra meals after the lock (BR-25). Scope is
 * checked by the access interceptor; list queries are filtered by its list scope.
 */
@Service
public class AdminRegistrationsService {
  private static final int MAX_LIST = 1000;
  private static final Collator VIETNAMESE = Collator.getInstance(Locale.forLanguageTag("vi"));

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final OrgDirectory org;
  private final RegistrationStore store;
  private final ForecastService forecast;
  private final SettingsService settings;
  private final AuditService audit;
  private final ClockService clock;

  public AdminRegistrationsService(
      JdbcClient jdbc,
      TransactionTemplate tx,
      OrgDirectory org,
      RegistrationStore store,
      ForecastService forecast,
      SettingsService settings,
      AuditService audit,
      ClockService clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.org = org;
    this.store = store;
    this.forecast = forecast;
    this.settings = settings;
    this.audit = audit;
    this.clock = clock;
  }

  private static <T> Comparator<T> vietnamese(java.util.function.Function<T, String> key) {
    return (a, b) -> VIETNAMESE.compare(key.apply(a), key.apply(b));
  }

  private static List<String> pathIds(String path) {
    return Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toList();
  }

  /** Org nodes the caller may open a grid or a form for, with how many can register below. */
  public List<UnitOption> units(ListScope scope, String factoryId) {
    OrgSnapshot snapshot = org.load(factoryId);
    Map<String, Object> params = new HashMap<>();
    String where = EmployeeScopeSql.where(scope, params);
    Map<String, Integer> counts = new HashMap<>();
    for (Employee e : store.employees(factoryId, where, params, snapshot)) {
      OrgSnapshot.Node node = snapshot.get(e.orgUnitId());
      if (!e.registrable() || node == null) {
        continue;
      }
      pathIds(node.path()).forEach(id -> counts.merge(id, 1, Integer::sum));
    }
    return snapshot.nodes().stream()
        .filter(n -> n.active() && inScope(scope, n.path()) && counts.getOrDefault(n.id(), 0) > 0)
        .map(
            n -> {
              String pathName = snapshot.pathName(n.id());
              return new UnitOption(
                  n.id(),
                  n.name(),
                  pathName.isEmpty() ? n.name() : pathName,
                  n.level(),
                  n.isMealUnit(),
                  counts.get(n.id()));
            })
        .sorted(vietnamese(UnitOption::pathName))
        .toList();
  }

  private static boolean inScope(ListScope scope, String path) {
    return !(scope instanceof ListScope.Paths p) || p.paths().stream().anyMatch(path::startsWith);
  }

  /** Khối / Trung tâm / Phòng ban filter options within the caller's scope. */
  public List<OrgSnapshot.FilterOption> orgUnits(ListScope scope, String factoryId) {
    return org.load(factoryId).filterOptions(scope);
  }

  private record Meals(Map<String, Integer> byEmployee, Map<String, LoadedWeek> weeks) {}

  /** Meals per employee in the week (or one day) of each employee's own canteen menu. */
  private Meals mealsByEmployee(List<Employee> employees, LocalDate weekStart, LocalDate date) {
    Map<String, LoadedWeek> weeks = new HashMap<>();
    for (Employee e : employees) {
      String canteenId = e.assignment().canteenId();
      if (!weeks.containsKey(canteenId)) {
        weeks.put(canteenId, store.loadWeek(canteenId, weekStart));
      }
    }
    Set<LocalDate> dates = new LinkedHashSet<>();
    if (date != null) {
      dates.add(date);
    } else {
      weeks.values().stream()
          .filter(Objects::nonNull)
          .flatMap(w -> w.servingDays().stream())
          .forEach(d -> dates.add(d.date()));
    }
    Map<String, Integer> meals = new HashMap<>();
    if (!employees.isEmpty() && !dates.isEmpty()) {
      jdbc.sql(
              """
              SELECT "employeeId", SUM(quantity) FROM "Registration"
              WHERE "employeeId" IN (:ids) AND date IN (:dates) AND status = 'ACTIVE'
                AND quantity > 0
              GROUP BY "employeeId"
              """)
          .param("ids", employees.stream().map(Employee::id).toList())
          .param("dates", List.copyOf(dates))
          .query((rs, i) -> meals.put(rs.getString(1), rs.getInt(2)))
          .list();
    }
    return new Meals(meals, weeks);
  }

  private record Scoped(OrgSnapshot org, List<Employee> employees) {}

  private Scoped scopedRegistrable(ListScope scope, String factoryId, LocalDate weekStart) {
    OrgSnapshot snapshot = org.load(factoryId);
    Map<String, Object> params = new HashMap<>();
    String where = EmployeeScopeSql.where(scope, params) + " AND e.status = 'ACTIVE'";
    List<Employee> employees =
        store.employees(factoryId, where, params, snapshot).stream()
            .filter(Employee::registrable)
            // BR-36: someone who left before the week starts has no meals to register.
            .filter(e -> !e.leftBy(weekStart))
            .toList();
    return new Scoped(snapshot, employees);
  }

  /** /admin/registrations: progress by meal unit for the week. */
  public ProgressView progress(ListScope scope, String factoryId, LocalDate weekStart) {
    assertMonday(weekStart);
    Scoped scoped = scopedRegistrable(scope, factoryId, weekStart);
    Meals meals = mealsByEmployee(scoped.employees(), weekStart, null);
    Map<String, int[]> counts = new LinkedHashMap<>();
    Map<String, LoadedWeek> weekOf = new HashMap<>();
    for (Employee e : scoped.employees()) {
      String id = e.assignment().mealUnitId();
      if (!weekOf.containsKey(id)) {
        // The unit shows the menu of its first employee's canteen.
        weekOf.put(id, meals.weeks().get(e.assignment().canteenId()));
      }
      int[] c = counts.computeIfAbsent(id, k -> new int[4]);
      int n = meals.byEmployee().getOrDefault(e.id(), 0);
      c[0] += 1;
      c[3] += n;
      c[n > 0 ? 1 : 2] += 1;
    }
    List<ProgressUnit> units = new ArrayList<>();
    counts.forEach(
        (id, c) -> {
          OrgSnapshot.Node node = scoped.org().get(id);
          LoadedWeek week = weekOf.get(id);
          units.add(
              new ProgressUnit(
                  id,
                  node == null ? id : node.name(),
                  scoped.org().pathName(id),
                  week == null ? null : week.canteenName(),
                  week != null && week.published(),
                  c[0],
                  c[1],
                  c[2],
                  c[3]));
        });
    units.sort(vietnamese(ProgressUnit::pathName));
    return new ProgressView(weekStart, units);
  }

  /**
   * BR-21: employees in scope with no meal in the week (or on {@code date}), from the moment their
   * canteen's menu is published (registration opens, BR-02).
   *
   * @param orgUnitId Khối / Trung tâm / Phòng ban filter, on top of the scope (never widens it)
   */
  public NotRegisteredView notRegistered(
      ListScope scope, String factoryId, LocalDate weekStart, LocalDate date, String orgUnitId) {
    assertMonday(weekStart);
    Scoped scoped = scopedRegistrable(scope, factoryId, weekStart);
    OrgSnapshot snapshot = scoped.org();
    OrgSnapshot.Node under = orgUnitId == null ? null : snapshot.get(orgUnitId);
    List<Employee> employees =
        orgUnitId == null
            ? scoped.employees()
            : scoped.employees().stream()
                .filter(
                    e -> {
                      OrgSnapshot.Node node = snapshot.get(e.orgUnitId());
                      return under != null && node != null && node.path().startsWith(under.path());
                    })
                .toList();
    Meals meals = mealsByEmployee(employees, weekStart, date);
    List<NotRegisteredItem> items =
        employees.stream()
            .filter(
                e -> {
                  LoadedWeek week = meals.weeks().get(e.assignment().canteenId());
                  if (week == null || !week.published()) {
                    return false;
                  }
                  if (date != null) {
                    LoadedDay day = week.day(date);
                    if (day == null || !day.isServing() || e.leftBy(date)) {
                      return false;
                    }
                  }
                  return meals.byEmployee().getOrDefault(e.id(), 0) == 0;
                })
            .map(
                e -> {
                  OrgSnapshot.Node unit = snapshot.get(e.assignment().mealUnitId());
                  return new NotRegisteredItem(
                      e.employeeCode(),
                      e.name(),
                      snapshot.pathName(e.orgUnitId()),
                      unit == null ? "" : unit.name());
                })
            .sorted(
                vietnamese(NotRegisteredItem::orgPath)
                    .thenComparing(vietnamese(NotRegisteredItem::name)))
            .toList();
    return new NotRegisteredView(
        weekStart, date, items.size(), items.subList(0, Math.min(items.size(), MAX_LIST)));
  }

  private record UnitEmployees(OrgSnapshot org, OrgSnapshot.Node unit, List<Employee> all) {}

  /** Employees below {@code orgUnitId} with their assignment; the scope was checked already. */
  private UnitEmployees unitEmployees(String factoryId, String orgUnitId, LocalDate weekStart) {
    OrgSnapshot snapshot = org.load(factoryId);
    OrgSnapshot.Node unit = snapshot.get(orgUnitId);
    if (unit == null) {
      throw ApiException.notFound("Org unit not found");
    }
    List<Employee> all =
        store.employees(
            factoryId,
            """
            starts_with(o.path, :unitPath)
            AND (e.status = 'ACTIVE' OR e."endDate" > :weekStart)
            """,
            Map.of("unitPath", unit.path(), "weekStart", weekStart),
            snapshot);
    return new UnitEmployees(snapshot, unit, all);
  }

  /**
   * Bulk-entry grid and printable forms (plan 4.1): employees below an org node, one column per
   * meal of each serving day. Employees of another canteen are counted, not shown, so every row
   * uses the same menu.
   */
  public GridView grid(String factoryId, String orgUnitId, LocalDate weekStart, String canteenId) {
    assertMonday(weekStart);
    forecast.catchUp(factoryId);
    UnitEmployees loaded = unitEmployees(factoryId, orgUnitId, weekStart);
    OrgSnapshot snapshot = loaded.org();
    List<Employee> candidates =
        loaded.all().stream()
            .filter(e -> e.assignment().canRegister() && e.assignment().canteenId() != null)
            .toList();
    Map<String, Integer> canteenCounts = new LinkedHashMap<>();
    candidates.forEach(e -> canteenCounts.merge(e.assignment().canteenId(), 1, Integer::sum));
    List<CanteenRef> canteens =
        canteenCounts.isEmpty()
            ? List.of()
            : jdbc.sql("SELECT id, name FROM \"Canteen\" WHERE id IN (:ids) ORDER BY code")
                .param("ids", List.copyOf(canteenCounts.keySet()))
                .query(CanteenRef.class)
                .list();
    String chosen =
        canteenId != null && canteenCounts.containsKey(canteenId)
            ? canteenId
            : canteenCounts.entrySet().stream()
                .reduce((a, b) -> b.getValue() > a.getValue() ? b : a)
                .map(Map.Entry::getKey)
                .orElse(null);
    List<Employee> rowsOf =
        candidates.stream().filter(e -> e.assignment().canteenId().equals(chosen)).toList();
    String pathName = snapshot.pathName(loaded.unit().id());
    OrgUnitRef unitRef =
        new OrgUnitRef(
            loaded.unit().id(),
            loaded.unit().name(),
            pathName.isEmpty() ? loaded.unit().name() : pathName);
    String canteenName =
        canteens.stream()
            .filter(c -> c.id().equals(chosen))
            .map(CanteenRef::name)
            .findFirst()
            .orElse(null);
    GridSkipped skipped =
        new GridSkipped(
            candidates.size() - rowsOf.size(),
            (int)
                loaded.all().stream()
                    .filter(e -> e.status().equals("ACTIVE") && !e.assignment().canRegister())
                    .count());
    if (chosen == null || rowsOf.isEmpty()) {
      return new GridView(
          "NO_EMPLOYEES",
          unitRef,
          weekStart,
          canteens,
          chosen,
          canteenName,
          null,
          false,
          List.of(),
          List.of(),
          skipped);
    }
    LoadedWeek week = store.loadWeek(chosen, weekStart);
    if (week == null || !week.published()) {
      return new GridView(
          "NOT_PUBLISHED",
          unitRef,
          weekStart,
          canteens,
          chosen,
          canteenName,
          null,
          false,
          List.of(),
          List.of(),
          skipped);
    }

    Instant now = clock.now();
    LocalTime lockTime = settings.getTime(factoryId, SettingKey.DAY_LOCK_TIME);
    List<LoadedDay> serving = week.servingDays();
    List<String> ids = rowsOf.stream().map(Employee::id).toList();
    List<RegRow> regs = store.rowsOf(ids, serving.stream().map(LoadedDay::date).toList());
    Map<String, Map<LocalDate, Map<String, Integer>>> forecastLines =
        week.closedAt() == null ? null : store.forecastOf(week.id(), ids);
    List<GridDay> days =
        serving.stream()
            .map(
                d ->
                    new GridDay(
                        d.date(),
                        // TODO(BR-25): `finalized` comes from DayClose in P4.
                        RegistrationRules.adminDayWindow(now, d.date(), lockTime, false),
                        Deadlines.dayLockAt(d.date(), lockTime),
                        d.items().stream()
                            .map(i -> new GridItem(i.id(), i.displayName(), i.type()))
                            .toList()))
            .toList();
    List<GridRow> rows =
        rowsOf.stream()
            .map(
                e -> {
                  Map<LocalDate, GridCellView> cells = new LinkedHashMap<>();
                  for (LoadedDay d : serving) {
                    List<RegRow> mine =
                        regs.stream()
                            .filter(r -> r.employeeId().equals(e.id()) && r.date().equals(d.date()))
                            .toList();
                    cells.put(
                        d.date(),
                        new GridCellView(
                            activeQuantities(mine),
                            forecastLines == null
                                ? null
                                : forecastLines
                                    .getOrDefault(e.id(), Map.of())
                                    .getOrDefault(d.date(), Map.of()),
                            mine.stream().anyMatch(r -> r.isExtra() && r.activeMeal()),
                            mine.stream()
                                .filter(r -> r.status() == RegistrationRules.Status.NEEDS_RESELECT)
                                .mapToInt(RegRow::quantity)
                                .sum(),
                            e.leftBy(d.date())));
                  }
                  return new GridRow(
                      e.employeeCode(), e.name(), snapshot.pathName(e.orgUnitId()), cells);
                })
            .toList();
    return new GridView(
        "PUBLISHED",
        unitRef,
        weekStart,
        canteens,
        chosen,
        canteenName,
        week.closeAt(),
        week.closedAt() != null,
        days,
        rows,
        skipped);
  }

  /** One employee's whole-day selection in a grid save. */
  public record GridRowInput(String employeeCode, List<Line> lines) {}

  public record GridDayInput(LocalDate date, List<GridRowInput> rows) {}

  /**
   * Saves one day of the grid as a whole batch (plan 4.1), on behalf of the employees, until the
   * day's lock (BR-21). Any invalid row rejects the batch with row-level errors. Every changed
   * employee-day is audited (BR-70).
   */
  public GridSaveResult saveGridDay(
      String actorId,
      String factoryId,
      String orgUnitId,
      LocalDate date,
      String canteenId,
      List<GridRowInput> rows) {
    return saveGridDays(
        actorId, factoryId, orgUnitId, canteenId, List.of(new GridDayInput(date, rows)));
  }

  /**
   * The grid save for several days of one canteen's week at once (the Excel import): every day is
   * validated first, then all are written in one transaction, so a file is applied whole or not at
   * all. Same rules and audit as one grid day.
   */
  public GridSaveResult saveGridDays(
      String actorId,
      String factoryId,
      String orgUnitId,
      String canteenId,
      List<GridDayInput> days) {
    forecast.closeDueWeeks(factoryId);
    LocalTime lockTime = settings.getTime(factoryId, SettingKey.DAY_LOCK_TIME);
    LoadedWeek week = null;
    String unitPath = "";
    OrgSnapshot snapshot = null;
    Map<String, Employee> byCode = new HashMap<>();
    List<Map<String, String>> errors = new ArrayList<>();

    for (GridDayInput input : days) {
      LocalDate date = input.date();
      PublishedDay found = store.publishedDay(canteenId, date);
      if (found == null
          || !found.week().factoryId().equals(factoryId)
          || (week != null && !found.week().id().equals(week.id()))) {
        throw ApiException.invalidState(
            "No published serving day for this canteen and date (BR-02)",
            Map.of("date", date.toString()));
      }
      if (week == null) {
        week = found.week();
        UnitEmployees loaded = unitEmployees(factoryId, orgUnitId, week.weekStart());
        snapshot = loaded.org();
        unitPath = loaded.unit().path();
      }
      if (RegistrationRules.adminDayWindow(clock.now(), date, lockTime, false)
          != AdminDayWindow.ON_BEHALF) {
        throw dayLocked(date);
      }
      List<String> missing =
          input.rows().stream()
              .map(GridRowInput::employeeCode)
              .filter(c -> !byCode.containsKey(c))
              .distinct()
              .toList();
      if (!missing.isEmpty()) {
        store
            .employees(
                factoryId, "e.\"employeeCode\" IN (:codes)", Map.of("codes", missing), snapshot)
            .forEach(e -> byCode.put(e.employeeCode(), e));
      }
      Set<String> itemIds = found.day().itemIds();
      Set<String> seen = new HashSet<>();
      String onDate = days.size() > 1 ? date.toString() : null;
      for (GridRowInput row : input.rows()) {
        Employee e = byCode.get(row.employeeCode());
        boolean hasMeals = row.lines().stream().anyMatch(l -> l.quantity() > 0);
        if (!seen.add(row.employeeCode())) {
          errors.add(rowError(row.employeeCode(), "DUPLICATE_ROW", null, onDate));
          continue;
        }
        OrgSnapshot.Node node = e == null ? null : snapshot.get(e.orgUnitId());
        if (node == null || !node.path().startsWith(unitPath)) {
          errors.add(rowError(row.employeeCode(), "NOT_IN_UNIT", null, onDate));
          continue;
        }
        if (!e.assignment().canRegister() || !canteenId.equals(e.assignment().canteenId())) {
          errors.add(rowError(row.employeeCode(), "CANNOT_REGISTER", null, onDate));
          continue;
        }
        if (hasMeals && e.leftBy(date)) {
          errors.add(rowError(row.employeeCode(), "LEFT", null, onDate));
          continue;
        }
        RegistrationRules.validateDaySelection(row.lines(), itemIds)
            .forEach(
                x ->
                    errors.add(
                        rowError(row.employeeCode(), x.code().name(), x.menuItemId(), onDate)));
      }
    }
    if (!errors.isEmpty()) {
      throw ApiException.invalid("Invalid grid rows", errors);
    }
    if (week == null) {
      return new GridSaveResult(0, 0, 0);
    }
    String weekId = week.id();
    Set<String> changed = new HashSet<>();
    int[] meals = {0};
    tx.executeWithoutResult(
        status -> {
          boolean forecastTaken = store.lockWeek(weekId);
          for (GridDayInput input : days) {
            LocalDate date = input.date();
            Instant now = clock.now();
            if (RegistrationRules.adminDayWindow(now, date, lockTime, false)
                != AdminDayWindow.ON_BEHALF) {
              throw dayLocked(date);
            }
            List<String> ids =
                input.rows().stream().map(r -> byCode.get(r.employeeCode()).id()).toList();
            List<RegRow> existing = store.rowsOf(ids, List.of(date));
            for (GridRowInput row : input.rows()) {
              Employee e = byCode.get(row.employeeCode());
              Applied res =
                  store.applyDay(
                      e.id(),
                      date,
                      RegistrationRules.quantitiesOf(row.lines()),
                      existing.stream().filter(r -> r.employeeId().equals(e.id())).toList(),
                      "ADMIN",
                      now);
              meals[0] += RegistrationStore.sum(res.after());
              if (!res.changed()) {
                continue;
              }
              changed.add(e.id());
              Map<String, Object> after = new LinkedHashMap<>();
              after.put("quantities", res.after());
              after.put("menuWeekId", weekId);
              after.put("afterClose", forecastTaken);
              audit.log(
                  AuditService.Entry.of(
                          actorId,
                          "registration.onBehalf",
                          "RegistrationDay",
                          e.employeeCode() + ":" + date)
                      .withBefore(Map.of("quantities", res.before()))
                      .withAfter(after));
            }
          }
        });
    return new GridSaveResult(changed.size(), meals[0], days.size());
  }

  private static Map<String, String> rowError(
      String employeeCode, String code, String menuItemId, String date) {
    Map<String, String> error = new LinkedHashMap<>();
    error.put("employeeCode", employeeCode);
    error.put("code", code);
    if (menuItemId != null) {
      error.put("menuItemId", menuItemId);
    }
    if (date != null) {
      error.put("date", date);
    }
    return error;
  }

  /**
   * BR-25: after the day's lock and before finalization, an admin adds meals flagged "Extra" (new
   * hires, forgotten registrations), with a required reason. Audited (BR-70).
   */
  public ExtraResult addExtra(
      String actorId,
      String factoryId,
      String employeeCode,
      LocalDate date,
      String menuItemId,
      int quantity,
      String reason) {
    Employee e =
        store
            .employees(factoryId, "e.\"employeeCode\" = :code", Map.of("code", employeeCode), null)
            .stream()
            .findFirst()
            .orElseThrow(() -> ApiException.notFound("Employee not found"));
    if (!e.assignment().canRegister() || e.assignment().canteenId() == null) {
      throw ApiException.invalidState("Employee has no valid meal unit (BR-60)", null);
    }
    if (e.leftBy(date)) {
      throw ApiException.invalidState("Employee has left (BR-36)", null);
    }
    PublishedDay found = store.publishedDay(e.assignment().canteenId(), date);
    if (found == null) {
      throw ApiException.invalidState("No published serving day for this date (BR-02)", null);
    }
    if (!found.day().itemIds().contains(menuItemId)) {
      throw ApiException.invalid(
          "Invalid selection (BR-20)",
          List.of(Map.of("code", "ITEM_NOT_ON_MENU", "menuItemId", menuItemId)));
    }
    LocalTime lockTime = settings.getTime(factoryId, SettingKey.DAY_LOCK_TIME);
    // TODO(BR-25): `finalized` comes from DayClose in P4.
    AdminDayWindow window = RegistrationRules.adminDayWindow(clock.now(), date, lockTime, false);
    if (window == AdminDayWindow.ON_BEHALF) {
      throw ApiException.invalidState("Before the lock, register on behalf instead (BR-21)", null);
    }
    if (window == AdminDayWindow.CLOSED) {
      throw dayLocked(date);
    }

    forecast.closeDueWeeks(factoryId);
    return tx.execute(
        status -> {
          store.lockWeek(found.week().id());
          Instant now = clock.now();
          if (RegistrationRules.adminDayWindow(now, date, lockTime, false)
              != AdminDayWindow.EXTRA) {
            throw dayLocked(date);
          }
          RegRow existing =
              store.rowsOf(List.of(e.id()), List.of(date)).stream()
                  .filter(r -> r.menuItemId().equals(menuItemId))
                  .findFirst()
                  .orElse(null);
          ExtraAdded res =
              store.addExtra(e.id(), date, menuItemId, quantity, reason, existing, now);
          Map<String, Object> before = new LinkedHashMap<>();
          before.put("menuItemId", menuItemId);
          before.put("quantity", res.before());
          Map<String, Object> after = new LinkedHashMap<>();
          after.put("menuItemId", menuItemId);
          after.put("quantity", res.after());
          after.put("isExtra", true);
          audit.log(
              AuditService.Entry.of(
                      actorId,
                      "registration.extraAdded",
                      "RegistrationDay",
                      e.employeeCode() + ":" + date)
                  .withBefore(before)
                  .withAfter(after)
                  .withReason(reason));
          return new ExtraResult(e.employeeCode(), date, res.before(), res.after());
        });
  }
}
