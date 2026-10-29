package vn.pnj.meal.api.registrations;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.pnj.meal.api.common.ApiErrorCode;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.db.SqlTime;
import vn.pnj.meal.api.dishes.DishTypeLabel;
import vn.pnj.meal.api.org.OrgDirectory;
import vn.pnj.meal.api.org.OrgSnapshot;
import vn.pnj.meal.api.registrations.RegistrationViews.MyDayView;
import vn.pnj.meal.api.registrations.RegistrationViews.NeedsReselect;
import vn.pnj.meal.api.registrations.RegistrationViews.RegistrationLineView;
import vn.pnj.meal.api.registrations.RegistrationViews.ReplacedFrom;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.deadline.Deadlines;
import vn.pnj.meal.rules.menu.MenuRules;
import vn.pnj.meal.rules.org.Mapping;
import vn.pnj.meal.rules.org.Mapping.EmployeeMealAssignment;
import vn.pnj.meal.rules.registration.RegistrationRules;
import vn.pnj.meal.rules.registration.RegistrationRules.Comparison;
import vn.pnj.meal.rules.registration.RegistrationRules.Status;

/**
 * Shared internals of the registration flows: loading a canteen's week, resolving who may register
 * where (BR-60, BR-63), the week lock that orders writes against the forecast snapshot (BR-07), and
 * applying a day's selection (BR-20). Runs in the caller's transaction.
 */
@Component
public class RegistrationStore {
  /** The MVP serves lunch only (plan section 1). */
  static final String SESSION = "LUNCH";

  private final JdbcClient jdbc;
  private final OrgDirectory org;

  public RegistrationStore(JdbcClient jdbc, OrgDirectory org) {
    this.jdbc = jdbc;
    this.org = org;
  }

  static void assertMonday(LocalDate weekStart) {
    if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
      throw ApiException.invalid(
          "weekStart must be a Monday", List.of(Map.of("path", "weekStart", "message", "monday")));
    }
  }

  static ApiException dayLocked(LocalDate date) {
    return new ApiException(
        HttpStatus.CONFLICT,
        ApiErrorCode.DAY_LOCKED,
        "Day is locked",
        Map.of("date", date.toString()),
        null);
  }

  /**
   * @param replaced BR-14: items whose dish this one replaced, with when they were replaced
   */
  record LoadedItem(String id, String displayName, DishTypeLabel type, List<Replaced> replaced) {}

  record Replaced(String id, String displayName, Instant removedAt) {}

  record LoadedDay(
      LocalDate date, boolean isServing, List<LoadedItem> items, List<String> desserts) {
    Set<String> itemIds() {
      Set<String> ids = new LinkedHashSet<>();
      items.forEach(i -> ids.add(i.id()));
      return ids;
    }
  }

  /**
   * @param published BR-02: employees never see or register on a draft
   * @param closedAt BR-07: set when the forecast snapshot was taken
   */
  record LoadedWeek(
      String id,
      String factoryId,
      String canteenId,
      String canteenName,
      LocalDate weekStart,
      boolean published,
      Instant closeAt,
      Instant closedAt,
      List<LoadedDay> days) {

    List<LoadedDay> servingDays() {
      return days.stream().filter(LoadedDay::isServing).toList();
    }

    LoadedDay day(LocalDate date) {
      return days.stream().filter(d -> d.date().equals(date)).findFirst().orElse(null);
    }
  }

  private record Head(
      String id,
      String factoryId,
      String canteenId,
      String canteenName,
      String status,
      Instant closeAt,
      Instant closedAt) {}

  private record ItemRow(
      String menuDayId, String id, String dishName, String typeId, String typeName, String color) {}

  LoadedWeek loadWeek(String canteenId, LocalDate weekStart) {
    Head head =
        jdbc.sql(
                """
                SELECT w.id, w."factoryId", w."canteenId", c.name AS "canteenName",
                       w.status::text, w."closeAt", w."closedAt"
                FROM "MenuWeek" w JOIN "Canteen" c ON c.id = w."canteenId"
                WHERE w."canteenId" = :canteenId AND w."weekStart" = :weekStart
                """)
            .param("canteenId", canteenId)
            .param("weekStart", weekStart)
            .query(
                (rs, i) ->
                    new Head(
                        rs.getString("id"),
                        rs.getString("factoryId"),
                        rs.getString("canteenId"),
                        rs.getString("canteenName"),
                        rs.getString("status"),
                        SqlTime.instant(rs, "closeAt"),
                        SqlTime.instant(rs, "closedAt")))
            .optional()
            .orElse(null);
    if (head == null) {
      return null;
    }
    Map<String, List<Replaced>> replacedBy = new HashMap<>();
    jdbc.sql(
            """
            SELECT r."replacedByItemId" AS "byId", r.id, r."removedAt", d.name AS "dishName",
                   t.name AS "typeName"
            FROM "MenuItem" r
            JOIN "Dish" d ON d.id = r."dishId"
            JOIN "DishType" t ON t.id = d."typeId"
            JOIN "MenuItem" n ON n.id = r."replacedByItemId"
            JOIN "MenuDay" md ON md.id = n."menuDayId"
            WHERE md."menuWeekId" = :id
            ORDER BY r.id
            """)
        .param("id", head.id())
        .query(
            (rs, i) ->
                replacedBy
                    .computeIfAbsent(rs.getString("byId"), k -> new ArrayList<>())
                    .add(
                        new Replaced(
                            rs.getString("id"),
                            MenuRules.mealDisplayName(
                                rs.getString("typeName"), rs.getString("dishName")),
                            SqlTime.instant(rs, "removedAt"))))
        .list();
    Map<String, List<LoadedItem>> items = new HashMap<>();
    jdbc.sql(
            """
            SELECT i."menuDayId", i.id, d.name AS "dishName", t.id AS "typeId",
                   t.name AS "typeName", t.color
            FROM "MenuItem" i
            JOIN "MenuDay" md ON md.id = i."menuDayId"
            JOIN "Dish" d ON d.id = i."dishId"
            JOIN "DishType" t ON t.id = d."typeId"
            WHERE md."menuWeekId" = :id AND i."removedAt" IS NULL AND i.session = 'LUNCH'
            ORDER BY i."sortOrder", i.id
            """)
        .param("id", head.id())
        .query(ItemRow.class)
        .list()
        .forEach(
            r ->
                items
                    .computeIfAbsent(r.menuDayId(), k -> new ArrayList<>())
                    .add(
                        new LoadedItem(
                            r.id(),
                            MenuRules.mealDisplayName(r.typeName(), r.dishName()),
                            new DishTypeLabel(r.typeId(), r.typeName(), r.color()),
                            List.copyOf(replacedBy.getOrDefault(r.id(), List.of())))));
    Map<String, List<String>> desserts = new HashMap<>();
    jdbc.sql(
            """
            SELECT x."menuDayId", d.name
            FROM "MenuDayDessert" x
            JOIN "MenuDay" md ON md.id = x."menuDayId"
            JOIN "Dessert" d ON d.id = x."dessertId"
            WHERE md."menuWeekId" = :id AND x."removedAt" IS NULL
            ORDER BY x."sortOrder", x.id
            """)
        .param("id", head.id())
        .query(
            (rs, i) ->
                desserts
                    .computeIfAbsent(rs.getString("menuDayId"), k -> new ArrayList<>())
                    .add(rs.getString("name")))
        .list();
    List<LoadedDay> days =
        jdbc.sql(
                "SELECT id, date, \"isServing\" FROM \"MenuDay\" WHERE \"menuWeekId\" = :id ORDER BY date")
            .param("id", head.id())
            .query(
                (rs, i) ->
                    new LoadedDay(
                        rs.getObject("date", LocalDate.class),
                        rs.getBoolean("isServing"),
                        List.copyOf(items.getOrDefault(rs.getString("id"), List.of())),
                        List.copyOf(desserts.getOrDefault(rs.getString("id"), List.of()))))
            .list();
    return new LoadedWeek(
        head.id(),
        head.factoryId(),
        head.canteenId(),
        head.canteenName(),
        weekStart,
        !head.status().equals("DRAFT") && head.closeAt() != null,
        head.closeAt(),
        head.closedAt(),
        days);
  }

  record PublishedDay(LoadedWeek week, LoadedDay day) {}

  /** The published week of {@code canteenId} that contains {@code date}, with that day, or null. */
  PublishedDay publishedDay(String canteenId, LocalDate date) {
    LoadedWeek week = loadWeek(canteenId, VnTime.mondayOf(date));
    LoadedDay day = week == null ? null : week.day(date);
    if (week == null || !week.published() || day == null || !day.isServing()) {
      return null;
    }
    return new PublishedDay(week, day);
  }

  /**
   * @param status ACTIVE or LEFT
   */
  record Employee(
      String id,
      String employeeCode,
      String name,
      String status,
      String orgUnitId,
      LocalDate endDate,
      EmployeeMealAssignment assignment) {

    /** BR-36: an employee loses their meals from their end date on. */
    boolean leftBy(LocalDate date) {
      return (endDate != null && !date.isBefore(endDate)) || status.equals("LEFT");
    }

    /** Can register into a canteen, i.e. is not on the "unassigned" list (BR-60, BR-63). */
    boolean registrable() {
      return status.equals("ACTIVE") && assignment.canRegister() && assignment.canteenId() != null;
    }
  }

  private record EmployeeRow(
      String id,
      String employeeCode,
      String name,
      String status,
      String orgUnitId,
      LocalDate endDate,
      String floorOverrideId,
      String dmpCostCenterCode) {}

  /**
   * BR-60 / BR-63 for the employees of a factory matching {@code where} (SQL over {@code "Employee"
   * e JOIN "OrgUnit" o}), by name then code.
   */
  List<Employee> employees(
      String factoryId, String where, Map<String, Object> params, OrgSnapshot snapshot) {
    OrgSnapshot tree = snapshot != null ? snapshot : org.load(factoryId);
    Map<String, String> ccByCode = org.costCenterIdsByCode(factoryId);
    Map<String, Object> all = new HashMap<>(params);
    all.put("factoryId", factoryId);
    return jdbc
        .sql(
            """
            SELECT e.id, e."employeeCode", e.name, e.status::text, e."orgUnitId", e."endDate",
                   e."floorOverrideId", e."dmpCostCenterCode"
            FROM "Employee" e JOIN "OrgUnit" o ON o.id = e."orgUnitId"
            WHERE o."factoryId" = :factoryId AND (%s)
            ORDER BY e.name, e."employeeCode"
            """
                .formatted(where))
        .params(all)
        .query(EmployeeRow.class)
        .list()
        .stream()
        .map(
            e ->
                new Employee(
                    e.id(),
                    e.employeeCode(),
                    e.name(),
                    e.status(),
                    e.orgUnitId(),
                    e.endDate(),
                    Mapping.resolveEmployeeMealAssignment(
                        e.orgUnitId(),
                        e.floorOverrideId(),
                        e.dmpCostCenterCode() == null ? null : ccByCode.get(e.dmpCostCenterCode()),
                        tree.index())))
        .toList();
  }

  Employee employee(String factoryId, String employeeId) {
    return employees(factoryId, "e.id = :employeeId", Map.of("employeeId", employeeId), null)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Employee " + employeeId + " not found"));
  }

  /**
   * Takes a shared lock on the week row for the rest of the transaction, and reports whether the
   * forecast was already taken. The snapshot takes the row's exclusive lock, so a write either
   * lands before the snapshot (and is in the forecast) or after it (and is a difference, BR-23).
   */
  boolean lockWeek(String menuWeekId) {
    return jdbc.sql("SELECT \"closedAt\" FROM \"MenuWeek\" WHERE id = :id FOR SHARE")
        .param("id", menuWeekId)
        .query((rs, i) -> rs.getObject(1) != null)
        .optional()
        .orElse(false);
  }

  /**
   * A stored registration row as the flows need it.
   *
   * @param replacedFromItemId BR-14: set when the HC admin replaced the dish and this meal moved
   * @param displayName the meal's dish as shown (BR-12)
   */
  record RegRow(
      String id,
      String employeeId,
      String menuItemId,
      LocalDate date,
      int quantity,
      Status status,
      boolean isExtra,
      String replacedFromItemId,
      String displayName) {

    boolean activeMeal() {
      return status == Status.ACTIVE && quantity > 0;
    }
  }

  List<RegRow> rowsOf(List<String> employeeIds, List<LocalDate> dates) {
    if (employeeIds.isEmpty() || dates.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            SELECT r.id, r."employeeId", r."menuItemId", r.date, r.quantity, r.status::text,
                   r."isExtra", r."replacedFromItemId", d.name AS "dishName", t.name AS "typeName"
            FROM "Registration" r
            JOIN "MenuItem" i ON i.id = r."menuItemId"
            JOIN "Dish" d ON d.id = i."dishId"
            JOIN "DishType" t ON t.id = d."typeId"
            WHERE r."employeeId" IN (:employeeIds) AND r.date IN (:dates) AND r.session = 'LUNCH'
            ORDER BY r.id
            """)
        .param("employeeIds", employeeIds)
        .param("dates", dates)
        .query(
            (rs, i) ->
                new RegRow(
                    rs.getString("id"),
                    rs.getString("employeeId"),
                    rs.getString("menuItemId"),
                    rs.getObject("date", LocalDate.class),
                    rs.getInt("quantity"),
                    Status.valueOf(rs.getString("status")),
                    rs.getBoolean("isExtra"),
                    rs.getString("replacedFromItemId"),
                    MenuRules.mealDisplayName(rs.getString("typeName"), rs.getString("dishName"))))
        .list();
  }

  /** Positive quantities of ACTIVE rows by menu item. */
  static Map<String, Integer> activeQuantities(List<RegRow> rows) {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (RegRow r : rows) {
      if (r.activeMeal()) {
        out.merge(r.menuItemId(), r.quantity(), Integer::sum);
      }
    }
    return out;
  }

  static boolean sameQuantities(Map<String, Integer> a, Map<String, Integer> b) {
    Set<String> keys = new LinkedHashSet<>(a.keySet());
    keys.addAll(b.keySet());
    return keys.stream().allMatch(k -> Objects.equals(a.getOrDefault(k, 0), b.getOrDefault(k, 0)));
  }

  static int sum(Map<String, Integer> quantities) {
    return quantities.values().stream().mapToInt(Integer::intValue).sum();
  }

  record Applied(boolean changed, Map<String, Integer> before, Map<String, Integer> after) {}

  /**
   * BR-20: makes the employee's active lines of {@code date} equal {@code after} (items not listed
   * become 0). Rows are never deleted (rule 7): a removed meal keeps its row with quantity 0.
   * Registrations waiting for reselection (BR-14) are resolved, i.e. cancelled, once the day is
   * saved with a new selection.
   *
   * @param after positive quantities by menu item
   * @param source SELF or ADMIN
   */
  Applied applyDay(
      String employeeId,
      LocalDate date,
      Map<String, Integer> after,
      List<RegRow> existing,
      String source,
      Instant now) {
    Map<String, Integer> before = activeQuantities(existing);
    if (sameQuantities(before, after)) {
      return new Applied(false, before, after);
    }
    Map<String, RegRow> byItem = new HashMap<>();
    existing.forEach(r -> byItem.put(r.menuItemId(), r));
    Set<String> itemIds = new LinkedHashSet<>(before.keySet());
    itemIds.addAll(after.keySet());
    for (String itemId : itemIds) {
      int want = after.getOrDefault(itemId, 0);
      RegRow row = byItem.get(itemId);
      if (row == null) {
        jdbc.sql(
                """
                INSERT INTO "Registration" (id, "employeeId", "menuItemId", date, session,
                    quantity, source, "createdAt", "updatedAt")
                VALUES (:id, :employeeId, :menuItemId, :date, 'LUNCH', :quantity,
                    CAST(:source AS "RegistrationSource"), :now, :now)
                """)
            .param("id", Ids.newId(now.toEpochMilli()))
            .param("employeeId", employeeId)
            .param("menuItemId", itemId)
            .param("date", date)
            .param("quantity", want)
            .param("source", source)
            .param("now", SqlTime.utc(now))
            .update();
      } else if (row.quantity() != want || (want > 0 && row.status() != Status.ACTIVE)) {
        jdbc.sql(
                """
                UPDATE "Registration" SET quantity = :quantity, status = 'ACTIVE',
                    source = CAST(:source AS "RegistrationSource"), "updatedAt" = :now
                WHERE id = :id
                """)
            .param("quantity", want)
            .param("source", source)
            .param("now", SqlTime.utc(now))
            .param("id", row.id())
            .update();
      }
    }
    List<String> pending =
        existing.stream().filter(r -> r.status() == Status.NEEDS_RESELECT).map(RegRow::id).toList();
    if (!pending.isEmpty()) {
      jdbc.sql(
              """
              UPDATE "Registration" SET status = 'CANCELLED', "updatedAt" = :now
              WHERE id IN (:ids)
              """)
          .param("now", SqlTime.utc(now))
          .param("ids", pending)
          .update();
    }
    return new Applied(true, before, after);
  }

  record ExtraAdded(int before, int after) {}

  /**
   * BR-25: adds {@code quantity} meals flagged "Extra" with the reason. The registration row of the
   * item is reused (one row per employee and item); the audit log keeps every addition.
   */
  ExtraAdded addExtra(
      String employeeId,
      LocalDate date,
      String menuItemId,
      int quantity,
      String reason,
      RegRow existing,
      Instant now) {
    int before = existing != null && existing.status() == Status.ACTIVE ? existing.quantity() : 0;
    int after = before + quantity;
    if (existing != null) {
      jdbc.sql(
              """
              UPDATE "Registration" SET quantity = :quantity, status = 'ACTIVE', "isExtra" = TRUE,
                  "extraReason" = :reason, source = 'ADMIN', "updatedAt" = :now
              WHERE id = :id
              """)
          .param("quantity", after)
          .param("reason", reason)
          .param("now", SqlTime.utc(now))
          .param("id", existing.id())
          .update();
    } else {
      jdbc.sql(
              """
              INSERT INTO "Registration" (id, "employeeId", "menuItemId", date, session, quantity,
                  source, "isExtra", "extraReason", "createdAt", "updatedAt")
              VALUES (:id, :employeeId, :menuItemId, :date, 'LUNCH', :quantity, 'ADMIN', TRUE,
                  :reason, :now, :now)
              """)
          .param("id", Ids.newId(now.toEpochMilli()))
          .param("employeeId", employeeId)
          .param("menuItemId", menuItemId)
          .param("date", date)
          .param("quantity", after)
          .param("reason", reason)
          .param("now", SqlTime.utc(now))
          .update();
    }
    return new ExtraAdded(before, after);
  }

  /** Forecast lines of employees in a week: employee id → date → menu item → quantity. */
  Map<String, Map<LocalDate, Map<String, Integer>>> forecastOf(
      String menuWeekId, List<String> employeeIds) {
    Map<String, Map<LocalDate, Map<String, Integer>>> out = new HashMap<>();
    if (employeeIds.isEmpty()) {
      return out;
    }
    jdbc.sql(
            """
            SELECT "employeeId", date, "menuItemId", quantity FROM "ForecastLine"
            WHERE "menuWeekId" = :id AND "employeeId" IN (:ids)
            ORDER BY id
            """)
        .param("id", menuWeekId)
        .param("ids", employeeIds)
        .query(
            (rs, i) ->
                out.computeIfAbsent(rs.getString("employeeId"), k -> new HashMap<>())
                    .computeIfAbsent(
                        rs.getObject("date", LocalDate.class), k -> new LinkedHashMap<>())
                    .merge(rs.getString("menuItemId"), rs.getInt("quantity"), Integer::sum))
        .list();
    return out;
  }

  /**
   * The employee's view of one serving day (BR-22 to BR-24): current quantities, the forecast once
   * taken, meals waiting for reselection and the difference from the forecast.
   *
   * @param forecast null until the snapshot exists
   */
  static MyDayView dayView(
      LoadedWeek week,
      LoadedDay day,
      List<RegRow> rows,
      Map<String, Integer> forecast,
      Instant now,
      LocalTime lockTime,
      LocalDate endDate) {
    Map<String, Integer> current = activeQuantities(rows);
    Comparison comparison =
        RegistrationRules.compareWithForecast(forecast == null ? Map.of() : forecast, current);
    boolean leaver = endDate != null && !day.date().isBefore(endDate);
    return new MyDayView(
        day.date(),
        leaver
            ? RegistrationRules.EmployeeDayWindow.LOCKED
            : RegistrationRules.employeeDayWindow(
                now, day.date(), week.closeAt(), lockTime, week.closedAt() != null),
        Deadlines.dayLockAt(day.date(), lockTime),
        day.items().stream()
            .map(
                i ->
                    new RegistrationLineView(
                        i.id(),
                        i.displayName(),
                        i.type(),
                        current.getOrDefault(i.id(), 0),
                        forecast == null ? 0 : forecast.getOrDefault(i.id(), 0),
                        replacedAfterClose(week, i, rows)))
            .toList(),
        rows.stream()
            .filter(r -> r.status() == Status.NEEDS_RESELECT && r.quantity() > 0)
            .map(r -> new NeedsReselect(r.displayName(), r.quantity()))
            .toList(),
        day.desserts(),
        comparison.actualTotal(),
        forecast == null ? null : comparison.forecastTotal(),
        forecast != null && comparison.changed(),
        rows.stream().anyMatch(r -> r.isExtra() && r.activeMeal()));
  }

  /**
   * BR-14 (product owner, 2026-10-23): when the HC admin replaces a dish after closeAt, an employee
   * whose meal moved to the new dish sees a red note naming the dish they had chosen. Only that
   * employee: someone who picks the new dish themselves gets no note.
   */
  static ReplacedFrom replacedAfterClose(LoadedWeek week, LoadedItem item, List<RegRow> rows) {
    if (week.closeAt() == null) {
      return null;
    }
    for (RegRow r : rows) {
      if (!r.menuItemId().equals(item.id()) || !r.activeMeal()) {
        continue;
      }
      for (Replaced from : item.replaced()) {
        if (from.id().equals(r.replacedFromItemId())
            && from.removedAt() != null
            && !from.removedAt().isBefore(week.closeAt())) {
          return new ReplacedFrom(from.displayName());
        }
      }
    }
    return null;
  }
}
