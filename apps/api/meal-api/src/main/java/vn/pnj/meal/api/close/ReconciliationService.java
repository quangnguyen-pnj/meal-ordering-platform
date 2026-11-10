package vn.pnj.meal.api.close;

import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.pnj.meal.api.attendance.AttendanceService;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.close.CloseViews.ForecastCanteen;
import vn.pnj.meal.api.close.CloseViews.ForecastDay;
import vn.pnj.meal.api.close.CloseViews.ForecastMeal;
import vn.pnj.meal.api.close.CloseViews.ForecastWeekView;
import vn.pnj.meal.api.close.CloseViews.ReconciliationMeal;
import vn.pnj.meal.api.close.CloseViews.ReconciliationSummary;
import vn.pnj.meal.api.close.CloseViews.ReconciliationView;
import vn.pnj.meal.api.common.ApiErrorCode;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.db.SqlTime;
import vn.pnj.meal.api.org.OrgDirectory;
import vn.pnj.meal.api.org.OrgSnapshot;
import vn.pnj.meal.api.registrations.AdminTodayService;
import vn.pnj.meal.api.registrations.ForecastService;
import vn.pnj.meal.api.registrations.RegistrationViews.AdminTodayView;
import vn.pnj.meal.api.registrations.RegistrationViews.TodayLine;
import vn.pnj.meal.api.registrations.RegistrationViews.TodayRow;
import vn.pnj.meal.api.settings.SettingKey;
import vn.pnj.meal.api.settings.SettingsService;
import vn.pnj.meal.rules.access.ListScope;
import vn.pnj.meal.rules.close.CountGrid;
import vn.pnj.meal.rules.close.DayCloseRules;
import vn.pnj.meal.rules.close.Reconciliation;

/**
 * /admin/reconciliation (BR-40 to BR-42) and the caterer's files (BR-44). The per-employee part
 * (meals, forecast, presence, cases) is the same computation as /admin/today, so both screens
 * always agree; this adds the per-meal comparison, the final count and the adjustments.
 */
@Service
public class ReconciliationService {
  private static final Collator VIETNAMESE = Collator.getInstance(Locale.forLanguageTag("vi"));

  private final AdminTodayService today;
  private final DayCloseStore days;
  private final CloseData data;
  private final SettingsService settings;
  private final ClockService clock;
  private final OrgDirectory org;
  private final ForecastService forecast;
  private final JdbcClient jdbc;

  public ReconciliationService(
      AdminTodayService today,
      DayCloseStore days,
      CloseData data,
      SettingsService settings,
      ClockService clock,
      OrgDirectory org,
      ForecastService forecast,
      JdbcClient jdbc) {
    this.today = today;
    this.days = days;
    this.data = data;
    this.settings = settings;
    this.clock = clock;
    this.org = org;
    this.forecast = forecast;
    this.jdbc = jdbc;
  }

  private static final class MealSum {
    final CloseData.Item item;
    int forecast;
    int counted;

    MealSum(CloseData.Item item) {
      this.item = item;
    }
  }

  public ReconciliationView view(
      ListScope scope, String factoryId, LocalDate date, @Nullable String orgUnitId) {
    AdminTodayView day = today.view(scope, factoryId, date, orgUnitId);
    DayCloseStore.Closed closed = days.closed(factoryId, date);
    String unitPath = null;
    if (orgUnitId != null) {
      OrgSnapshot.Node node = org.load(factoryId).get(orgUnitId);
      unitPath = node == null ? null : node.path();
    }
    Map<String, Integer> finals =
        closed == null ? Map.of() : data.scopedByItem(false, closed.id(), scope, unitPath);
    Map<String, Integer> adjustments =
        closed == null ? Map.of() : data.scopedByItem(true, closed.id(), scope, unitPath);

    Set<String> ids = new LinkedHashSet<>();
    day.menus().forEach(m -> m.items().forEach(i -> ids.add(i.id())));
    day.rows().forEach(r -> r.lines().forEach(l -> ids.add(l.menuItemId())));
    ids.addAll(finals.keySet());
    ids.addAll(adjustments.keySet());
    Map<String, CloseData.Item> items = data.items(ids);
    Map<String, MealSum> sums = new LinkedHashMap<>();
    items.values().stream()
        .sorted(
            Comparator.comparing(CloseData.Item::canteenName, VIETNAMESE)
                .thenComparingInt(CloseData.Item::sortOrder)
                .thenComparing(CloseData.Item::id))
        .forEach(i -> sums.put(i.id(), new MealSum(i)));
    for (TodayRow row : day.rows()) {
      for (TodayLine line : row.lines()) {
        MealSum sum = sums.get(line.menuItemId());
        if (sum == null) {
          continue;
        }
        sum.forecast += line.forecast();
        if (row.mealsCount()) {
          sum.counted += line.quantity();
        }
      }
    }
    double threshold =
        settings.get(factoryId, SettingKey.RECONCILIATION_DISH_DEVIATION_PCT).asDouble();
    List<ReconciliationMeal> meals = new ArrayList<>();
    int forecastTotal = 0;
    int actualTotal = 0;
    int finalTotal = 0;
    int adjustmentTotal = 0;
    int deviating = 0;
    for (MealSum s : sums.values()) {
      int fin = finals.getOrDefault(s.item.id(), 0);
      int adj = adjustments.getOrDefault(s.item.id(), 0);
      int actual = closed != null ? fin : s.counted;
      if (s.forecast == 0 && actual == 0 && adj == 0 && !onMenu(day, s.item.id())) {
        continue;
      }
      boolean deviates = Reconciliation.deviates(s.forecast, actual, threshold);
      deviating += deviates ? 1 : 0;
      forecastTotal += s.forecast;
      actualTotal += actual;
      finalTotal += fin;
      adjustmentTotal += adj;
      meals.add(
          new ReconciliationMeal(
              s.item.id(),
              s.item.displayName(),
              s.item.canteenId(),
              s.item.canteenName(),
              s.forecast,
              actual,
              actual - s.forecast,
              Reconciliation.status(s.forecast, actual).name(),
              deviates,
              Reconciliation.deviationPct(s.forecast, actual),
              closed == null ? null : fin,
              closed == null ? null : adj,
              closed == null ? null : Reconciliation.effective(fin, adj)));
    }
    List<TodayRow> exceptions = day.rows().stream().filter(r -> !r.cases().isEmpty()).toList();
    Instant now = clock.now();
    Instant opensAt = days.finalizeOpensAt(factoryId, date);
    AttendanceService.CutoffSummary cutoff = day.attendance().cutoff();
    return new ReconciliationView(
        date,
        now,
        day.attendance().servingDay(),
        data.forecastTaken(factoryId, date),
        ClosedView.of(closed),
        day.attendance().servingDay() && closed == null && !now.isBefore(opensAt),
        opensAt,
        days.autoFinalizeAt(factoryId, date),
        DayCloseRules.adjustmentDeadlineAt(date, days.adjustmentDeadline(factoryId)),
        DayCloseRules.adjustmentWindow(
                now, date, closed != null, days.adjustmentDeadline(factoryId), false)
            == DayCloseRules.AdjustmentWindow.OPEN,
        cutoff != null && !cutoff.outcome().equals("CANCELLED"),
        threshold,
        day.attendance(),
        new ReconciliationSummary(
            forecastTotal,
            actualTotal,
            actualTotal - forecastTotal,
            exceptions.size(),
            deviating,
            closed == null ? null : finalTotal,
            closed == null ? null : adjustmentTotal,
            closed == null ? null : finalTotal + adjustmentTotal),
        List.copyOf(meals),
        exceptions);
  }

  private static boolean onMenu(AdminTodayView day, String itemId) {
    return day.menus().stream()
        .anyMatch(m -> m.items().stream().anyMatch(i -> i.id().equals(itemId)));
  }

  // --- files (BR-44) -----------------------------------------------------------------------------

  /** BR-44: the final file of a finalized day, with its box-label sheet. */
  public byte[] finalFile(String factoryId, LocalDate date) {
    DayCloseStore.Closed closed = days.closed(factoryId, date);
    if (closed == null) {
      throw ApiException.invalidState(
          "Day is not finalized (BR-43)", Map.of("date", date.toString()));
    }
    List<CountGrid.Cell> cells = data.finalCells(closed.id());
    CountGrid.Grid grid = CountGrid.build(data.columns(factoryId, date, cells), cells, false);
    return CloseWorkbooks.finalFile(date, closed.finalizedAt(), grid);
  }

  private record WeekCanteen(String id, String canteenId, String canteenName, Instant closedAt) {}

  private List<WeekCanteen> weekCanteens(String factoryId, LocalDate weekStart) {
    return jdbc.sql(
            """
            SELECT w.id, w."canteenId", c.name AS "canteenName", w."closedAt"
            FROM "MenuWeek" w JOIN "Canteen" c ON c.id = w."canteenId"
            WHERE w."factoryId" = :factoryId AND w."weekStart" = :weekStart AND w.status <> 'DRAFT'
            ORDER BY c.name, c.id
            """)
        .param("factoryId", factoryId)
        .param("weekStart", weekStart)
        .query(
            (rs, i) ->
                new WeekCanteen(
                    rs.getString("id"),
                    rs.getString("canteenId"),
                    rs.getString("canteenName"),
                    SqlTime.instant(rs, "closedAt")))
        .list();
  }

  private List<LocalDate> servingDays(String factoryId, LocalDate weekStart) {
    return jdbc.sql(
            """
            SELECT DISTINCT d.date FROM "MenuDay" d JOIN "MenuWeek" w ON w.id = d."menuWeekId"
            WHERE w."factoryId" = :factoryId AND w."weekStart" = :weekStart
              AND w.status <> 'DRAFT' AND d."isServing"
            ORDER BY d.date
            """)
        .param("factoryId", factoryId)
        .param("weekStart", weekStart)
        .query(LocalDate.class)
        .list();
  }

  /** BR-07 / BR-44: the weekly forecast tab. */
  public ForecastWeekView forecastWeek(String factoryId, LocalDate weekStart) {
    forecast.closeDueWeeks(factoryId);
    List<WeekCanteen> canteens = weekCanteens(factoryId, weekStart);
    List<LocalDate> serving = servingDays(factoryId, weekStart);
    List<ForecastCanteen> out = new ArrayList<>();
    for (WeekCanteen c : canteens) {
      List<ForecastDay> dayViews = new ArrayList<>();
      if (c.closedAt() != null) {
        for (LocalDate date : serving) {
          List<CountGrid.Cell> cells =
              data.forecastCells(factoryId, date).stream()
                  .filter(x -> x.canteenId().equals(c.canteenId()))
                  .toList();
          Map<String, Integer> byItem = new LinkedHashMap<>();
          cells.forEach(x -> byItem.merge(x.menuItemId(), x.quantity(), Integer::sum));
          Map<String, CloseData.Item> items = data.items(byItem.keySet());
          List<ForecastMeal> list =
              byItem.entrySet().stream()
                  .sorted(
                      Comparator.comparingInt(
                          e ->
                              items.containsKey(e.getKey())
                                  ? items.get(e.getKey()).sortOrder()
                                  : 0))
                  .map(
                      e ->
                          new ForecastMeal(
                              items.containsKey(e.getKey())
                                  ? items.get(e.getKey()).displayName()
                                  : e.getKey(),
                              e.getValue()))
                  .toList();
          dayViews.add(
              new ForecastDay(date, list.stream().mapToInt(ForecastMeal::quantity).sum(), list));
        }
      }
      out.add(new ForecastCanteen(c.canteenId(), c.canteenName(), c.closedAt(), dayViews));
    }
    return new ForecastWeekView(
        weekStart, out.stream().anyMatch(c -> c.closedAt() != null), List.copyOf(out));
  }

  /** BR-07 / BR-44: the weekly forecast file, one sheet per serving day. */
  public byte[] forecastFile(String factoryId, LocalDate weekStart) {
    forecast.closeDueWeeks(factoryId);
    boolean taken = weekCanteens(factoryId, weekStart).stream().anyMatch(c -> c.closedAt() != null);
    if (!taken) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          ApiErrorCode.INVALID_STATE,
          "The forecast of this week is not taken yet (BR-07)",
          Map.of("weekStart", weekStart.toString()),
          null);
    }
    Map<LocalDate, CountGrid.Grid> grids = new LinkedHashMap<>();
    for (LocalDate date : servingDays(factoryId, weekStart)) {
      List<CountGrid.Cell> cells = data.forecastCells(factoryId, date);
      grids.put(date, CountGrid.build(data.columns(factoryId, date, cells), cells, false));
    }
    return CloseWorkbooks.forecastFile(weekStart, grids);
  }
}
