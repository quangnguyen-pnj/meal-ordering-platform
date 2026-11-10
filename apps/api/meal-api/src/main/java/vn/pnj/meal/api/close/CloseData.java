package vn.pnj.meal.api.close;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.pnj.meal.api.employees.EmployeeScopeSql;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.access.ListScope;
import vn.pnj.meal.rules.close.CountGrid;
import vn.pnj.meal.rules.menu.MenuRules;

/** SQL reads of the finalized day and of the forecast, shaped for reconciliation and the files. */
@Component
public class CloseData {
  private final JdbcClient jdbc;

  public CloseData(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** A meal (menu item) with its canteen and display name (BR-12). */
  public record Item(
      String id, String canteenId, String canteenName, String displayName, int sortOrder) {}

  /** The items, whatever their state (removed items keep their name for the files). */
  public Map<String, Item> items(Collection<String> ids) {
    Map<String, Item> out = new LinkedHashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    jdbc.sql(
            """
            SELECT i.id, w."canteenId", c.name AS "canteenName", d.name AS "dishName",
                   t.name AS "typeName", i."sortOrder"
            FROM "MenuItem" i
            JOIN "MenuDay" md ON md.id = i."menuDayId"
            JOIN "MenuWeek" w ON w.id = md."menuWeekId"
            JOIN "Canteen" c ON c.id = w."canteenId"
            JOIN "Dish" d ON d.id = i."dishId"
            JOIN "DishType" t ON t.id = d."typeId"
            WHERE i.id IN (:ids)
            """)
        .param("ids", List.copyOf(ids))
        .query(
            (rs, i) ->
                out.put(
                    rs.getString("id"),
                    new Item(
                        rs.getString("id"),
                        rs.getString("canteenId"),
                        rs.getString("canteenName"),
                        MenuRules.mealDisplayName(
                            rs.getString("typeName"), rs.getString("dishName")),
                        rs.getInt("sortOrder"))))
        .list();
    return out;
  }

  /** The current menu items of day D in every published menu of the factory, in menu order. */
  public List<Item> dayItems(String factoryId, LocalDate date) {
    List<String> ids =
        jdbc.sql(
                """
                SELECT i.id FROM "MenuItem" i
                JOIN "MenuDay" md ON md.id = i."menuDayId"
                JOIN "MenuWeek" w ON w.id = md."menuWeekId"
                WHERE w."factoryId" = :factoryId AND w.status <> 'DRAFT' AND md.date = :date
                  AND md."isServing" AND i."removedAt" IS NULL AND i.session = 'LUNCH'
                ORDER BY i."sortOrder", i.id
                """)
            .param("factoryId", factoryId)
            .param("date", date)
            .query(String.class)
            .list();
    return List.copyOf(items(ids).values());
  }

  /** The meal columns of a file: day D's menu items plus any other item the cells use. */
  public List<CountGrid.Meal> columns(
      String factoryId, LocalDate date, Collection<CountGrid.Cell> cells) {
    Map<String, Item> all = new LinkedHashMap<>();
    dayItems(factoryId, date).forEach(i -> all.put(i.id(), i));
    List<String> missing =
        cells.stream().map(CountGrid.Cell::menuItemId).filter(id -> !all.containsKey(id)).toList();
    all.putAll(items(missing));
    return all.values().stream()
        .map(i -> new CountGrid.Meal(i.canteenId(), i.id(), i.displayName(), i.sortOrder()))
        .toList();
  }

  private static final String CELL_COLUMNS =
      """
      x."canteenId", c.name AS "canteenName", x."floorId", f.name AS "floorName",
      COALESCE(f."sortOrder", 2147483647) AS "floorSort", x."mealUnitId",
      u.name AS "mealUnitName", x."menuItemId"
      """;

  private static final String CELL_JOINS =
      """
      JOIN "Canteen" c ON c.id = x."canteenId"
      LEFT JOIN "Floor" f ON f.id = x."floorId"
      LEFT JOIN "OrgUnit" u ON u.id = x."mealUnitId"
      """;

  /** BR-44: the final count of a finalized day by unit x floor x canteen x meal. */
  public List<CountGrid.Cell> finalCells(String dayCloseId) {
    return jdbc.sql(
            "SELECT "
                + CELL_COLUMNS
                + ", x.quantity FROM \"FinalCount\" x "
                + CELL_JOINS
                + " WHERE x.\"dayCloseId\" = :id")
        .param("id", dayCloseId)
        .query(CountGrid.Cell.class)
        .list();
  }

  /** BR-07 / BR-44: the forecast of day D with the mapping stored at closeAt (BR-64). */
  public List<CountGrid.Cell> forecastCells(String factoryId, LocalDate date) {
    return jdbc.sql(
            "SELECT "
                + CELL_COLUMNS
                + ", SUM(x.quantity)::int AS quantity FROM \"ForecastLine\" x "
                + CELL_JOINS
                + """
                 JOIN "MenuWeek" w ON w.id = x."menuWeekId"
                 WHERE w."factoryId" = :factoryId AND x.date = :date AND x.session = 'LUNCH'
                   AND x."canteenId" IS NOT NULL
                 GROUP BY x."canteenId", c.name, x."floorId", f.name, f."sortOrder",
                   x."mealUnitId", u.name, x."menuItemId"
                """)
        .param("factoryId", factoryId)
        .param("date", date)
        .query(CountGrid.Cell.class)
        .list();
  }

  /** BR-46: the cells one adjustment changes (signed). */
  public List<CountGrid.Cell> adjustmentCells(String adjustmentId) {
    return jdbc.sql(
            "SELECT "
                + CELL_COLUMNS
                + ", SUM(x.delta)::int AS quantity FROM \"AdjustmentLine\" x "
                + CELL_JOINS
                + """
                 WHERE x."adjustmentId" = :id
                 GROUP BY x."canteenId", c.name, x."floorId", f.name, f."sortOrder",
                   x."mealUnitId", u.name, x."menuItemId"
                """)
        .param("id", adjustmentId)
        .query(CountGrid.Cell.class)
        .list();
  }

  /**
   * Sums by menu item of a finalized day's employee lines ({@code FinalEmployeeLine}) or adjustment
   * lines, for the employees inside the scope (and below {@code unitPath} when set).
   */
  public Map<String, Integer> scopedByItem(
      boolean adjustments, String dayCloseId, ListScope scope, @Nullable String unitPath) {
    Map<String, Object> params = new HashMap<>();
    params.put("id", dayCloseId);
    String where = EmployeeScopeSql.where(scope, params);
    if (unitPath != null) {
      params.put("unitPath", unitPath);
      where += " AND starts_with(o.path, :unitPath)";
    }
    String from =
        adjustments
            ? """
              "AdjustmentLine" l JOIN "Adjustment" a ON a.id = l."adjustmentId"
              """
            : "\"FinalEmployeeLine\" l";
    String dayFilter = adjustments ? "a.\"dayCloseId\" = :id" : "l.\"dayCloseId\" = :id";
    String sum = adjustments ? "l.delta" : "l.quantity";
    Map<String, Integer> out = new LinkedHashMap<>();
    String sql =
        """
        SELECT l."menuItemId", SUM(%s)::int AS q FROM %s
        JOIN "Employee" e ON e.id = l."employeeId"
        JOIN "OrgUnit" o ON o.id = e."orgUnitId"
        WHERE %s AND (%s)
        GROUP BY l."menuItemId"
        """
            .formatted(sum, from.strip(), dayFilter, where);
    jdbc.sql(sql)
        .params(params)
        .query((rs, i) -> out.put(rs.getString("menuItemId"), rs.getInt("q")))
        .list();
    return out;
  }

  /** BR-07: whether the forecast of day D's week was taken in any canteen of the factory. */
  public boolean forecastTaken(String factoryId, LocalDate date) {
    return jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM "MenuWeek" WHERE "factoryId" = :factoryId
                             AND "weekStart" = :weekStart AND "closedAt" IS NOT NULL)
            """)
        .param("factoryId", factoryId)
        .param("weekStart", VnTime.mondayOf(date))
        .query(Boolean.class)
        .single();
  }

  /** Final line quantity and adjustment total of the employees, by employee id and menu item. */
  public Map<String, Map<String, int[]>> employeeLines(
      String dayCloseId, List<String> employeeIds) {
    Map<String, Map<String, int[]>> out = new HashMap<>();
    if (employeeIds.isEmpty()) {
      return out;
    }
    jdbc.sql(
            """
            SELECT "employeeId", "menuItemId", quantity, 0 AS kind FROM "FinalEmployeeLine"
            WHERE "dayCloseId" = :id AND "employeeId" IN (:ids)
            UNION ALL
            SELECT l."employeeId", l."menuItemId", l.delta, 1 FROM "AdjustmentLine" l
            JOIN "Adjustment" a ON a.id = l."adjustmentId"
            WHERE a."dayCloseId" = :id AND l."employeeId" IN (:ids)
            """)
        .param("id", dayCloseId)
        .param("ids", employeeIds)
        .query(
            (rs, i) ->
                out.computeIfAbsent(rs.getString("employeeId"), k -> new LinkedHashMap<>())
                        .computeIfAbsent(rs.getString("menuItemId"), k -> new int[2])[
                        rs.getInt("kind")] +=
                    rs.getInt("quantity"))
        .list();
    return out;
  }
}
