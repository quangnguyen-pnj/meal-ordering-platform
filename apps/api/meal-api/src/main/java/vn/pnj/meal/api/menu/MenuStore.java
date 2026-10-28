package vn.pnj.meal.api.menu;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.pnj.meal.api.db.SqlTime;

/** Reads a stored menu week with its current (not removed) items and desserts. */
@Component
class MenuStore {
  private final JdbcClient jdbc;

  MenuStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  record Item(
      String id, String dishId, String dishName, String typeId, String typeName, String color) {}

  /**
   * @param rowId the MenuDayDessert row
   */
  record Dessert(String rowId, String dessertId, String name, int sortOrder) {}

  record Day(
      String id, LocalDate date, boolean isServing, List<Item> items, List<Dessert> desserts) {}

  record Week(
      String id,
      String factoryId,
      String canteenId,
      LocalDate weekStart,
      String status,
      Instant publishedAt,
      Instant closeAt,
      List<Day> days) {

    boolean published() {
      return !status.equals("DRAFT");
    }
  }

  Week find(String canteenId, LocalDate weekStart) {
    return load(
        "w.\"canteenId\" = :canteenId AND w.\"weekStart\" = :weekStart",
        Map.of("canteenId", canteenId, "weekStart", weekStart));
  }

  Week byId(String id) {
    return load("w.id = :id", Map.of("id", id));
  }

  private record Head(
      String id,
      String factoryId,
      String canteenId,
      LocalDate weekStart,
      String status,
      Instant publishedAt,
      Instant closeAt) {}

  private Week load(String where, Map<String, Object> params) {
    Head head =
        jdbc.sql(
                """
                SELECT w.id, w."factoryId", w."canteenId", w."weekStart", w.status::text,
                       w."publishedAt", w."closeAt"
                FROM "MenuWeek" w WHERE\s"""
                    + where)
            .params(params)
            .query(
                (rs, i) ->
                    new Head(
                        rs.getString("id"),
                        rs.getString("factoryId"),
                        rs.getString("canteenId"),
                        rs.getObject("weekStart", LocalDate.class),
                        rs.getString("status"),
                        SqlTime.instant(rs, "publishedAt"),
                        SqlTime.instant(rs, "closeAt")))
            .optional()
            .orElse(null);
    if (head == null) {
      return null;
    }
    Map<String, List<Item>> items = new HashMap<>();
    jdbc.sql(
            """
            SELECT i."menuDayId", i.id, i."dishId", d.name AS "dishName", t.id AS "typeId",
                   t.name AS "typeName", t.color
            FROM "MenuItem" i
            JOIN "MenuDay" md ON md.id = i."menuDayId"
            JOIN "Dish" d ON d.id = i."dishId"
            JOIN "DishType" t ON t.id = d."typeId"
            WHERE md."menuWeekId" = :id AND i."removedAt" IS NULL
            ORDER BY i."sortOrder", i.id
            """)
        .param("id", head.id())
        .query(
            (rs, i) ->
                items
                    .computeIfAbsent(rs.getString("menuDayId"), k -> new ArrayList<>())
                    .add(
                        new Item(
                            rs.getString("id"),
                            rs.getString("dishId"),
                            rs.getString("dishName"),
                            rs.getString("typeId"),
                            rs.getString("typeName"),
                            rs.getString("color"))))
        .list();
    Map<String, List<Dessert>> desserts = new HashMap<>();
    jdbc.sql(
            """
            SELECT x."menuDayId", x.id, x."dessertId", d.name, x."sortOrder"
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
                    .add(
                        new Dessert(
                            rs.getString("id"),
                            rs.getString("dessertId"),
                            rs.getString("name"),
                            rs.getInt("sortOrder"))))
        .list();
    List<Day> days =
        jdbc.sql(
                """
                SELECT id, date, "isServing" FROM "MenuDay" WHERE "menuWeekId" = :id ORDER BY date
                """)
            .param("id", head.id())
            .query(
                (rs, i) ->
                    new Day(
                        rs.getString("id"),
                        rs.getObject("date", LocalDate.class),
                        rs.getBoolean("isServing"),
                        List.copyOf(items.getOrDefault(rs.getString("id"), List.of())),
                        List.copyOf(desserts.getOrDefault(rs.getString("id"), List.of()))))
            .list();
    return new Week(
        head.id(),
        head.factoryId(),
        head.canteenId(),
        head.weekStart(),
        head.status(),
        head.publishedAt(),
        head.closeAt(),
        days);
  }
}
