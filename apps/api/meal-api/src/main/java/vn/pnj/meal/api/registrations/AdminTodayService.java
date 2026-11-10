package vn.pnj.meal.api.registrations;

import static vn.pnj.meal.api.registrations.RegistrationStore.activeQuantities;

import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.pnj.meal.api.attendance.AttendanceService;
import vn.pnj.meal.api.attendance.AttendanceService.Presence;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.close.ClosedView;
import vn.pnj.meal.api.close.DayCloseService;
import vn.pnj.meal.api.close.DayCloseStore;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.employees.EmployeeScopeSql;
import vn.pnj.meal.api.org.OrgDirectory;
import vn.pnj.meal.api.org.OrgSnapshot;
import vn.pnj.meal.api.registrations.RegistrationStore.Applied;
import vn.pnj.meal.api.registrations.RegistrationStore.Employee;
import vn.pnj.meal.api.registrations.RegistrationStore.ForecastItem;
import vn.pnj.meal.api.registrations.RegistrationStore.PublishedDay;
import vn.pnj.meal.api.registrations.RegistrationStore.RegRow;
import vn.pnj.meal.api.registrations.RegistrationViews.AdminTodayView;
import vn.pnj.meal.api.registrations.RegistrationViews.GridItem;
import vn.pnj.meal.api.registrations.RegistrationViews.TodayEditResult;
import vn.pnj.meal.api.registrations.RegistrationViews.TodayLine;
import vn.pnj.meal.api.registrations.RegistrationViews.TodayMenu;
import vn.pnj.meal.api.registrations.RegistrationViews.TodayPresence;
import vn.pnj.meal.api.registrations.RegistrationViews.TodayRow;
import vn.pnj.meal.api.registrations.RegistrationViews.TodaySummary;
import vn.pnj.meal.api.settings.SettingKey;
import vn.pnj.meal.api.settings.SettingsService;
import vn.pnj.meal.rules.access.ListScope;
import vn.pnj.meal.rules.attendance.AttendanceRules;
import vn.pnj.meal.rules.attendance.AttendanceRules.PresenceState;
import vn.pnj.meal.rules.attendance.DayCases;
import vn.pnj.meal.rules.deadline.Deadlines;
import vn.pnj.meal.rules.registration.RegistrationRules;
import vn.pnj.meal.rules.registration.RegistrationRules.AdminDayWindow;
import vn.pnj.meal.rules.registration.RegistrationRules.Line;

/**
 * /admin/today (plan 4.1, P3): dept admins see their units, HC admins the factory. Shows every
 * employee with meals or a forecast on day D, with presence (BR-30 to BR-33), leave flags (BR-35)
 * and the cases to review (BR-41); admins confirm presence and edit meals after the lock with a
 * reason (BR-24, BR-25).
 */
@Service
public class AdminTodayService {
  private static final Collator VIETNAMESE = Collator.getInstance(Locale.forLanguageTag("vi"));

  private final TransactionTemplate tx;
  private final OrgDirectory org;
  private final RegistrationStore store;
  private final ForecastService forecast;
  private final SettingsService settings;
  private final AuditService audit;
  private final ClockService clock;
  private final DayCloseStore dayClose;
  private final DayCloseService finalizer;
  private final AttendanceService attendance;

  public AdminTodayService(
      TransactionTemplate tx,
      OrgDirectory org,
      RegistrationStore store,
      ForecastService forecast,
      SettingsService settings,
      AuditService audit,
      ClockService clock,
      DayCloseStore dayClose,
      DayCloseService finalizer,
      AttendanceService attendance) {
    this.tx = tx;
    this.org = org;
    this.store = store;
    this.forecast = forecast;
    this.settings = settings;
    this.audit = audit;
    this.clock = clock;
    this.dayClose = dayClose;
    this.finalizer = finalizer;
    this.attendance = attendance;
  }

  /**
   * @param orgUnitId narrows to employees below that node (inside the caller's scope)
   */
  public AdminTodayView view(ListScope scope, String factoryId, LocalDate date, String orgUnitId) {
    attendance.catchUpCutoff(factoryId, date);
    forecast.closeDueWeeks(factoryId);
    finalizer.finalizeDue(factoryId);
    DayCloseStore.Closed closed = dayClose.closed(factoryId, date);
    OrgSnapshot snapshot = org.load(factoryId);
    Map<String, Object> params = new HashMap<>();
    StringBuilder where = new StringBuilder("(").append(EmployeeScopeSql.where(scope, params));
    where.append(
        """
        ) AND (EXISTS (SELECT 1 FROM "Registration" r WHERE r."employeeId" = e.id
                         AND r.date = :date AND r.session = 'LUNCH')
            OR EXISTS (SELECT 1 FROM "ForecastLine" f WHERE f."employeeId" = e.id
                         AND f.date = :date))
        """);
    params.put("date", date);
    if (orgUnitId != null) {
      OrgSnapshot.Node under = snapshot.get(orgUnitId);
      if (under == null) {
        throw ApiException.notFound("Org unit not found");
      }
      params.put("unitPath", under.path());
      where.append(" AND starts_with(o.path, :unitPath)");
    }
    List<Employee> employees = store.employees(factoryId, where.toString(), params, snapshot);
    List<String> ids = employees.stream().map(Employee::id).toList();
    Map<String, List<RegRow>> rowsBy = new HashMap<>();
    store
        .rowsOf(ids, List.of(date))
        .forEach(r -> rowsBy.computeIfAbsent(r.employeeId(), k -> new ArrayList<>()).add(r));
    Map<String, List<ForecastItem>> forecastBy = new HashMap<>();
    store
        .forecastOn(date, ids)
        .forEach(f -> forecastBy.computeIfAbsent(f.employeeId(), k -> new ArrayList<>()).add(f));
    Map<String, Presence> presence = attendance.presenceOf(ids, date);
    Map<String, AttendanceService.LeaveFlag> leave = attendance.leaveOf(ids, date);

    Map<String, PublishedDay> days = new LinkedHashMap<>();
    for (Employee e : employees) {
      String canteenId = e.assignment().canteenId();
      if (canteenId != null && !days.containsKey(canteenId)) {
        days.put(canteenId, store.publishedDay(canteenId, date));
      }
    }
    List<TodayMenu> menus = new ArrayList<>();
    days.forEach(
        (canteenId, day) -> {
          if (day != null) {
            menus.add(
                new TodayMenu(
                    canteenId,
                    day.week().canteenName(),
                    day.day().items().stream()
                        .map(i -> new GridItem(i.id(), i.displayName(), i.type()))
                        .toList()));
          }
        });

    LocalTime cutoff = attendance.cutoffTime(factoryId);
    List<TodayRow> rows = new ArrayList<>();
    int withMeals = 0;
    int present = 0;
    int provisional = 0;
    int forecastMeals = 0;
    int actualMeals = 0;
    int countedMeals = 0;
    int cases = 0;
    for (Employee e : employees) {
      List<RegRow> regs = rowsBy.getOrDefault(e.id(), List.of());
      Map<String, Integer> actual = activeQuantities(regs);
      PublishedDay day =
          e.assignment().canteenId() == null ? null : days.get(e.assignment().canteenId());
      boolean forecastTaken = day != null && day.week().closedAt() != null;
      Map<String, Integer> fc = new LinkedHashMap<>();
      Map<String, String> names = new HashMap<>();
      for (ForecastItem f : forecastBy.getOrDefault(e.id(), List.of())) {
        fc.merge(f.menuItemId(), f.quantity(), Integer::sum);
        names.put(f.menuItemId(), f.displayName());
      }
      regs.forEach(r -> names.put(r.menuItemId(), r.displayName()));
      Set<String> onMenu = day == null ? Set.of() : day.day().itemIds();
      Set<String> itemIds = new LinkedHashSet<>(actual.keySet());
      itemIds.addAll(fc.keySet());
      List<TodayLine> lines =
          itemIds.stream()
              .map(
                  id ->
                      new TodayLine(
                          id,
                          names.getOrDefault(id, id),
                          actual.getOrDefault(id, 0),
                          fc.getOrDefault(id, 0),
                          onMenu.contains(id)))
              .toList();
      Presence p = presence.getOrDefault(e.id(), Presence.NONE);
      boolean inTime = AttendanceRules.checkInInTime(p.firstCheckInAt(), date, cutoff);
      AttendanceService.LeaveFlag flag = leave.get(e.id());
      // BR-32 (v1.4): what DMP sent for a cancelled employee after finalization is recorded only.
      boolean lateDmp =
          closed != null
              && p.state() == PresenceState.ABSENT
              && p.dmpConfirmedAt() != null
              && !p.dmpConfirmedAt().isBefore(closed.finalizedAt());
      List<DayCases.CaseType> rowCases =
          DayCases.casesFor(
              new DayCases.Input(
                  forecastTaken ? fc : null,
                  actual,
                  p.state(),
                  inTime,
                  p.dmpConfirmedAt() != null,
                  p.confirmedByAdmin(),
                  flag == null ? null : DayCases.LeaveDayType.valueOf(flag.dayType()),
                  regs.stream().anyMatch(r -> r.isExtra() && r.activeMeal()),
                  lateDmp,
                  p.lateCheckInAt() != null && p.state() == PresenceState.ABSENT));
      int actualTotal = RegistrationStore.sum(actual);
      int forecastTotal = RegistrationStore.sum(fc);
      boolean counts = AttendanceRules.mealsCount(p.state());
      if (actualTotal > 0) {
        withMeals++;
        if (p.state() == PresenceState.PRESENT) {
          present++;
        }
        if (p.state() == PresenceState.PROVISIONAL_ABSENT) {
          provisional++;
        }
      }
      forecastMeals += forecastTotal;
      actualMeals += actualTotal;
      countedMeals += counts ? actualTotal : 0;
      cases += rowCases.isEmpty() ? 0 : 1;
      rows.add(
          new TodayRow(
              e.employeeCode(),
              e.name(),
              snapshot.pathName(e.orgUnitId()),
              e.assignment().canteenId(),
              lines,
              forecastTaken ? forecastTotal : null,
              actualTotal,
              counts,
              new TodayPresence(
                  p.state(),
                  p.firstCheckInAt(),
                  inTime,
                  p.dmpConfirmedAt() != null,
                  p.confirmedByName(),
                  p.confirmedAt(),
                  p.confirmReason()),
              flag,
              rowCases));
    }
    rows.sort(
        Comparator.<TodayRow, String>comparing(TodayRow::orgPath, VIETNAMESE::compare)
            .thenComparing(TodayRow::name, VIETNAMESE::compare)
            .thenComparing(TodayRow::employeeCode));
    Instant now = clock.now();
    LocalTime lockTime = settings.getTime(factoryId, SettingKey.DAY_LOCK_TIME);
    return new AdminTodayView(
        date,
        now,
        dayClose.adminWindow(factoryId, date, now),
        Deadlines.dayLockAt(date, lockTime),
        AttendanceRules.canConfirmPresence(
            now, date, closed != null, dayClose.autoFinalizeAt(factoryId, date)),
        ClosedView.of(closed),
        attendance.status(factoryId, date),
        new TodaySummary(
            rows.size(),
            withMeals,
            present,
            provisional,
            forecastMeals,
            actualMeals,
            countedMeals,
            cases),
        menus,
        rows);
  }

  private Employee employeeByCode(String factoryId, String employeeCode) {
    return store
        .employees(factoryId, "e.\"employeeCode\" = :code", Map.of("code", employeeCode), null)
        .stream()
        .findFirst()
        .orElseThrow(() -> ApiException.notFound("Employee not found"));
  }

  /** BR-33: confirm presence (reason optional); the meals are restored. */
  public TodayPresence confirmPresence(
      String actorId, String factoryId, String employeeCode, LocalDate date, String reason) {
    Employee e = employeeByCode(factoryId, employeeCode);
    Presence p = attendance.confirm(actorId, factoryId, e.id(), e.employeeCode(), date, reason);
    LocalTime cutoff = attendance.cutoffTime(factoryId);
    return new TodayPresence(
        p.state(),
        p.firstCheckInAt(),
        AttendanceRules.checkInInTime(p.firstCheckInAt(), date, cutoff),
        p.dmpConfirmedAt() != null,
        p.confirmedByName(),
        p.confirmedAt(),
        p.confirmReason());
  }

  /**
   * BR-24: after 08:00 on day D and until finalization, an admin changes an employee's meals with a
   * reason. Meals added this way are flagged "Extra" with that reason (BR-25). Audited (BR-70).
   * Before the lock the admin registers on behalf instead (BR-21).
   */
  public TodayEditResult editMeals(
      String actorId,
      String factoryId,
      String employeeCode,
      LocalDate date,
      List<Line> lines,
      String reason) {
    Employee e = employeeByCode(factoryId, employeeCode);
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
    List<RegistrationRules.SelectionError> errors =
        RegistrationRules.validateDaySelection(lines, found.day().itemIds());
    if (!errors.isEmpty()) {
      throw ApiException.invalid(
          "Invalid selection (BR-20)",
          errors.stream()
              .map(x -> Map.of("code", x.code().name(), "menuItemId", x.menuItemId()))
              .toList());
    }
    LocalTime lockTime = settings.getTime(factoryId, SettingKey.DAY_LOCK_TIME);
    AdminDayWindow window = dayClose.adminWindow(factoryId, date, clock.now());
    if (window == AdminDayWindow.ON_BEHALF) {
      throw ApiException.invalidState("Before the lock, register on behalf instead (BR-21)", null);
    }
    if (window == AdminDayWindow.CLOSED) {
      throw dayClose.closedError(factoryId, date);
    }
    forecast.closeDueWeeks(factoryId);
    Map<String, Integer> wanted = RegistrationRules.quantitiesOf(lines);
    return tx.execute(
        status -> {
          store.lockWeek(found.week().id());
          Instant now = clock.now();
          if (dayClose.adminWindow(factoryId, date, now) != AdminDayWindow.EXTRA) {
            throw dayClose.closedError(factoryId, date);
          }
          Applied res =
              store.applyDay(
                  e.id(), date, wanted, store.rowsOf(List.of(e.id()), List.of(date)), "ADMIN", now);
          if (res.changed()) {
            Set<String> added = new LinkedHashSet<>();
            res.after()
                .forEach(
                    (id, q) -> {
                      if (q > res.before().getOrDefault(id, 0)) {
                        added.add(id);
                      }
                    });
            store.markExtra(e.id(), date, added, reason, now);
            audit.log(
                AuditService.Entry.of(
                        actorId,
                        "registration.adminEdit",
                        "RegistrationDay",
                        e.employeeCode() + ":" + date)
                    .withBefore(Map.of("quantities", res.before()))
                    .withAfter(Map.of("quantities", res.after(), "extraItems", added))
                    .withReason(reason));
          }
          return new TodayEditResult(e.employeeCode(), date, res.before(), res.after());
        });
  }
}
