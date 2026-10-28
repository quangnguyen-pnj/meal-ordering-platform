package vn.pnj.meal.api.menu;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.common.ApiErrorCode;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.db.SqlTime;
import vn.pnj.meal.api.org.OrgDirectory;
import vn.pnj.meal.api.settings.SettingKey;
import vn.pnj.meal.api.settings.SettingsService;
import vn.pnj.meal.rules.deadline.Deadlines;
import vn.pnj.meal.rules.deadline.WeekdayTime;
import vn.pnj.meal.rules.menu.MenuRules;
import vn.pnj.meal.rules.menu.MenuRules.DayCounts;
import vn.pnj.meal.rules.menu.MenuRules.DayGaps;
import vn.pnj.meal.rules.menu.MenuRules.EditResult;
import vn.pnj.meal.rules.menu.MenuRules.MixCheck;
import vn.pnj.meal.rules.menu.MenuRules.MixRule;
import vn.pnj.meal.rules.org.Mapping;
import vn.pnj.meal.rules.org.Mapping.EmployeeMealAssignment;

/**
 * Weekly menus per canteen (BR-08, BR-11, BR-13, BR-15), publishing and closeAt (BR-03..BR-05), and
 * edits of a published menu (BR-14).
 */
@Service
public class MenuService {
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final JsonMapper json;
  private final MenuStore store;
  private final SettingsService settings;
  private final AuditService audit;
  private final ClockService clock;
  private final OrgDirectory org;

  public MenuService(
      JdbcClient jdbc,
      TransactionTemplate tx,
      JsonMapper json,
      MenuStore store,
      SettingsService settings,
      AuditService audit,
      ClockService clock,
      OrgDirectory org) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.json = json;
    this.store = store;
    this.settings = settings;
    this.audit = audit;
    this.clock = clock;
    this.org = org;
  }

  /** 409 listing every serving day that lacks a main dish or a dessert (publishing rule). */
  private static ApiException incomplete(List<DayGaps> days) {
    return new ApiException(
        HttpStatus.CONFLICT,
        ApiErrorCode.MENU_INCOMPLETE,
        "Every serving day needs a main dish and a dessert",
        days,
        null);
  }

  static void assertMonday(LocalDate weekStart) {
    if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
      throw ApiException.invalid(
          "weekStart must be a Monday", List.of(Map.of("path", "weekStart", "message", "monday")));
    }
  }

  public record CanteenOption(String id, String code, String name) {}

  public List<CanteenOption> canteens(String factoryId) {
    return jdbc.sql(
            """
            SELECT id, code, name FROM "Canteen" WHERE "factoryId" = :factoryId AND active
            ORDER BY code
            """)
        .param("factoryId", factoryId)
        .query(CanteenOption.class)
        .list();
  }

  public record TypeLabel(String id, String name, String color) {}

  /**
   * @param displayName BR-12
   */
  public record MenuItemView(
      String id, String dishId, String dishName, TypeLabel type, String displayName) {}

  public record DessertLabel(String id, String name) {}

  /**
   * @param mix BR-13: a warning only, never blocks publishing; null for non-serving days
   */
  public record MenuDayView(
      LocalDate date,
      boolean isServing,
      List<MenuItemView> items,
      List<DessertLabel> desserts,
      MixCheck mix) {}

  /**
   * @param id null while nothing has been saved for this canteen and week
   * @param status DRAFT, PUBLISHED, CLOSED, or NEW when nothing is saved
   * @param closeAt stored once published; otherwise what publishing now would give (BR-03, BR-04)
   * @param lockedDates days whose lock has passed can no longer change once published
   */
  public record MenuWeekView(
      String id,
      String canteenId,
      String canteenName,
      LocalDate weekStart,
      String status,
      Instant publishedAt,
      Instant closeAt,
      boolean closeAtIsExpected,
      boolean latePublish,
      List<LocalDate> lockedDates,
      List<MenuDayView> days) {

    MenuWeekView servingDaysOnly() {
      return new MenuWeekView(
          id,
          canteenId,
          canteenName,
          weekStart,
          status,
          publishedAt,
          closeAt,
          closeAtIsExpected,
          latePublish,
          lockedDates,
          days.stream().filter(MenuDayView::isServing).toList());
    }
  }

  private record CanteenRow(String id, String factoryId, String name, boolean active) {}

  private CanteenRow canteen(String factoryId, String canteenId) {
    return jdbc.sql("SELECT id, \"factoryId\", name, active FROM \"Canteen\" WHERE id = :id")
        .param("id", canteenId)
        .query(CanteenRow.class)
        .optional()
        .filter(c -> c.factoryId().equals(factoryId))
        .orElseThrow(() -> ApiException.notFound("Canteen not found"));
  }

  /** The HC admin's view of one canteen's week; a week never saved shows its default days. */
  public MenuWeekView adminWeek(String factoryId, String canteenId, LocalDate weekStart) {
    assertMonday(weekStart);
    CanteenRow canteen = canteen(factoryId, canteenId);
    return view(factoryId, canteen, weekStart, store.find(canteenId, weekStart), true);
  }

  private MenuWeekView view(
      String factoryId,
      CanteenRow canteen,
      LocalDate weekStart,
      MenuStore.Week week,
      boolean withMix) {
    Instant now = clock.now();
    List<Integer> servingDays = settings.getWeekdays(factoryId, SettingKey.SERVING_DAYS);
    WeekdayTime closeRule = settings.getWeekdayTime(factoryId, SettingKey.REGISTRATION_CLOSE);
    int latePublishHours = settings.getInt(factoryId, SettingKey.REGISTRATION_LATE_PUBLISH_HOURS);
    LocalTime lockTime = settings.getTime(factoryId, SettingKey.DAY_LOCK_TIME);
    List<MixRule> mixRules = withMix ? mixRules(factoryId) : List.of();
    boolean published = week != null && week.published();

    Map<LocalDate, MenuStore.Day> stored = new HashMap<>();
    if (week != null) {
      week.days().forEach(d -> stored.put(d.date(), d));
    }
    Set<LocalDate> dateSet = new TreeSet<>(MenuRules.menuWeekDates(weekStart));
    dateSet.addAll(stored.keySet());
    List<LocalDate> dates = List.copyOf(dateSet);

    List<MenuDayView> days = new ArrayList<>();
    for (LocalDate date : dates) {
      MenuStore.Day d = stored.get(date);
      boolean isServing =
          d != null
              ? d.isServing()
              : !published && MenuRules.isDefaultServingDay(date, servingDays);
      List<MenuItemView> items =
          d == null
              ? List.of()
              : d.items().stream()
                  .map(
                      i ->
                          new MenuItemView(
                              i.id(),
                              i.dishId(),
                              i.dishName(),
                              new TypeLabel(i.typeId(), i.typeName(), i.color()),
                              MenuRules.mealDisplayName(i.typeName(), i.dishName())))
                  .toList();
      days.add(
          new MenuDayView(
              date,
              isServing,
              items,
              d == null
                  ? List.of()
                  : d.desserts().stream()
                      .map(x -> new DessertLabel(x.dessertId(), x.name()))
                      .toList(),
              withMix && isServing
                  ? MenuRules.checkMenuMix(
                      items.stream().map(i -> i.type().id()).toList(), mixRules)
                  : null));
    }

    Instant closeAt;
    boolean expected;
    boolean latePublish;
    if (published && week.closeAt() != null) {
      closeAt = week.closeAt();
      expected = false;
      latePublish =
          week.publishedAt() != null
              && Deadlines.computeCloseAt(
                      weekStart, week.publishedAt(), closeRule, latePublishHours)
                  .latePublish();
    } else {
      Deadlines.CloseAt r = Deadlines.computeCloseAt(weekStart, now, closeRule, latePublishHours);
      closeAt = r.closeAt();
      expected = true;
      latePublish = r.latePublish();
    }

    return new MenuWeekView(
        week == null ? null : week.id(),
        canteen.id(),
        canteen.name(),
        weekStart,
        week == null ? "NEW" : week.status(),
        week == null ? null : week.publishedAt(),
        closeAt,
        expected,
        latePublish,
        published
            ? dates.stream().filter(d -> !Deadlines.dayLockAt(d, lockTime).isAfter(now)).toList()
            : List.of(),
        days);
  }

  /**
   * @param id an existing item id (BR-14 replace); null for a new item
   */
  public record ItemInput(String id, String dishId) {}

  public record DayInput(
      LocalDate date, boolean isServing, List<ItemInput> items, List<String> dessertIds) {}

  /**
   * @param reason optional note for the audit log, e.g. "NCC đổi món" (BR-14, BR-70)
   */
  public record WeekInput(
      String canteenId, LocalDate weekStart, List<DayInput> days, String reason) {}

  public record Effects(int movedRegistrations, int flaggedRegistrations, int notifiedEmployees) {}

  public record MenuSaveResult(MenuWeekView menu, Effects effects) {}

  private record Notice(List<String> employeeIds, String type, Map<String, Object> payload) {}

  private record DishRow(
      String id, String canteenId, String name, String typeName, boolean archived, boolean lunch) {}

  private record DessertRow(String id, String canteenId, boolean archived) {}

  /**
   * BR-11: saves a whole week. On a published week this is an edit under BR-14: a replaced dish
   * moves its registrations to the replacement and notifies the employees; a removed dish sets its
   * registrations to "Needs reselection" and notifies them.
   */
  public MenuSaveResult save(String actorId, String factoryId, WeekInput input) {
    assertMonday(input.weekStart());
    CanteenRow canteen = canteen(factoryId, input.canteenId());
    if (!canteen.active()) {
      throw ApiException.invalidState("Canteen is inactive", null);
    }
    LocalDate weekEnd = input.weekStart().plusDays(6);
    if (input.days().stream().map(DayInput::date).distinct().count() != input.days().size()) {
      throw ApiException.invalid(
          "Each day may appear once", List.of(Map.of("path", "days", "message", "duplicate")));
    }
    for (DayInput day : input.days()) {
      if (day.date().isBefore(input.weekStart()) || day.date().isAfter(weekEnd)) {
        throw ApiException.invalid(
            "Day outside the week",
            List.of(Map.of("path", "days", "message", day.date().toString())));
      }
      if (!day.isServing() && (!day.items().isEmpty() || !day.dessertIds().isEmpty())) {
        throw ApiException.invalid(
            "A non-serving day has no dishes",
            List.of(Map.of("path", "days", "message", day.date().toString())));
      }
    }

    Instant now = clock.now();
    LocalTime lockTime = settings.getTime(factoryId, SettingKey.DAY_LOCK_TIME);
    int[] moved = {0};
    int[] flagged = {0};
    int[] notified = {0};

    tx.executeWithoutResult(
        status -> {
          MenuStore.Week week = store.find(canteen.id(), input.weekStart());
          if (week == null) {
            jdbc.sql(
                    """
                    INSERT INTO "MenuWeek" (id, "factoryId", "canteenId", "weekStart", "createdAt",
                        "updatedAt")
                    VALUES (:id, :factoryId, :canteenId, :weekStart, :now, :now)
                    """)
                .param("id", Ids.newId(now.toEpochMilli()))
                .param("factoryId", factoryId)
                .param("canteenId", canteen.id())
                .param("weekStart", input.weekStart())
                .param("now", SqlTime.utc(now))
                .update();
            week = store.find(canteen.id(), input.weekStart());
          }
          boolean published = week.published();
          Map<LocalDate, MenuStore.Day> storedDays = new HashMap<>();
          week.days().forEach(d -> storedDays.put(d.date(), d));
          Map<String, DishRow> dishes = loadDishes(factoryId, input, week);
          Map<String, DessertRow> desserts = loadDesserts(factoryId, input);

          Map<String, Object> before = new LinkedHashMap<>();
          Map<String, Object> after = new LinkedHashMap<>();
          // A published day keeps at least one main dish and one dessert (publishing rule).
          List<DayGaps> gaps = new ArrayList<>();
          List<Notice> notices = new ArrayList<>();

          for (DayInput day : input.days()) {
            MenuStore.Day stored = storedDays.get(day.date());
            List<MenuRules.ItemRef> currentItems =
                stored == null
                    ? List.of()
                    : stored.items().stream()
                        .map(i -> new MenuRules.ItemRef(i.id(), i.dishId()))
                        .toList();
            List<String> currentDesserts =
                stored == null
                    ? List.of()
                    : stored.desserts().stream().map(MenuStore.Dessert::dessertId).toList();
            boolean unchanged =
                stored != null
                    && stored.isServing() == day.isServing()
                    && currentItems.equals(
                        day.items().stream()
                            .map(i -> new MenuRules.ItemRef(i.id(), i.dishId()))
                            .toList())
                    && currentDesserts.equals(day.dessertIds());
            if (unchanged) {
              continue;
            }

            if (published) {
              if (!Deadlines.dayLockAt(day.date(), lockTime).isAfter(now)) {
                // TODO(BR-14): changing a day after its own lock is not covered by the plan
                // (open-questions Q15); refuse so locked days and later snapshots stay consistent.
                throw ApiException.invalidState(
                    "Day is locked", Map.of("date", day.date().toString()));
              }
              if (stored != null && stored.isServing() && !day.isServing()) {
                // TODO(BR-08): turning a published serving day off would strand registrations
                // (open-questions Q16).
                throw ApiException.invalidState(
                    "A published serving day cannot be turned off",
                    Map.of("date", day.date().toString()));
              }
              List<MenuRules.DayGap> dayGaps =
                  MenuRules.menuDayGaps(
                      day.isServing(), day.items().size(), day.dessertIds().size());
              if (!dayGaps.isEmpty()) {
                gaps.add(new DayGaps(day.date(), dayGaps));
              }
            }

            EditResult plan =
                MenuRules.planMenuDayEdit(
                    currentItems,
                    day.items().stream()
                        .map(i -> new MenuRules.DesiredItem(i.id(), i.dishId()))
                        .toList());
            if (!plan.ok()) {
              throw ApiException.invalid("Invalid menu items", editErrors(plan.errors()));
            }
            // New and replacement dishes must be in this canteen's library, not archived, and
            // served at lunch.
            List<String> incoming = new ArrayList<>();
            plan.plan().add().forEach(a -> incoming.add(a.dishId()));
            plan.plan().replace().forEach(r -> incoming.add(r.toDishId()));
            for (String dishId : incoming) {
              DishRow dish = dishes.get(dishId);
              if (dish == null
                  || dish.archived()
                  || !dish.canteenId().equals(canteen.id())
                  || !dish.lunch()) {
                throw ApiException.invalid(
                    "Dish not available", List.of(Map.of("path", "dishId", "message", dishId)));
              }
            }
            for (String dessertId : day.dessertIds()) {
              DessertRow dessert = desserts.get(dessertId);
              boolean isNew = !currentDesserts.contains(dessertId);
              if (dessert == null
                  || (isNew && (dessert.archived() || !dessert.canteenId().equals(canteen.id())))) {
                throw ApiException.invalid(
                    "Dessert not available",
                    List.of(Map.of("path", "dessertId", "message", dessertId)));
              }
            }

            String menuDayId;
            if (stored == null) {
              menuDayId = Ids.newId(now.toEpochMilli());
              jdbc.sql(
                      """
                      INSERT INTO "MenuDay" (id, "menuWeekId", date, "isServing")
                      VALUES (:id, :weekId, :date, :isServing)
                      """)
                  .param("id", menuDayId)
                  .param("weekId", week.id())
                  .param("date", day.date())
                  .param("isServing", day.isServing())
                  .update();
            } else {
              menuDayId = stored.id();
              if (stored.isServing() != day.isServing()) {
                jdbc.sql("UPDATE \"MenuDay\" SET \"isServing\" = :isServing WHERE id = :id")
                    .param("isServing", day.isServing())
                    .param("id", menuDayId)
                    .update();
              }
            }

            for (MenuRules.Keep k : plan.plan().keep()) {
              jdbc.sql("UPDATE \"MenuItem\" SET \"sortOrder\" = :sortOrder WHERE id = :id")
                  .param("sortOrder", k.sortOrder())
                  .param("id", k.id())
                  .update();
            }
            for (MenuRules.Add a : plan.plan().add()) {
              insertItem(menuDayId, a.dishId(), a.sortOrder(), now);
            }
            for (MenuRules.Replace r : plan.plan().replace()) {
              String replacement = insertItem(menuDayId, r.toDishId(), r.sortOrder(), now);
              jdbc.sql(
                      """
                      UPDATE "MenuItem" SET "removedAt" = :now, "replacedByItemId" = :replacement
                      WHERE id = :id
                      """)
                  .param("now", SqlTime.utc(now))
                  .param("replacement", replacement)
                  .param("id", r.fromId())
                  .update();
              // BR-14: registrations move to the replacement.
              List<String> employees = registrants(r.fromId(), "status <> 'CANCELLED'");
              // The old item is kept on the row, so the employee can be told which dish changed.
              moved[0] +=
                  jdbc.sql(
                          """
                          UPDATE "Registration" SET "menuItemId" = :replacement,
                              "replacedFromItemId" = :from, "updatedAt" = :now
                          WHERE "menuItemId" = :from AND status <> 'CANCELLED'
                          """)
                      .param("replacement", replacement)
                      .param("from", r.fromId())
                      .param("now", SqlTime.utc(now))
                      .update();
              if (!employees.isEmpty()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("date", day.date().toString());
                payload.put("from", displayName(dishes, r.fromDishId()));
                payload.put("to", displayName(dishes, r.toDishId()));
                notices.add(new Notice(employees, "MENU_DISH_REPLACED", payload));
              }
            }
            for (MenuRules.Remove r : plan.plan().remove()) {
              jdbc.sql("UPDATE \"MenuItem\" SET \"removedAt\" = :now WHERE id = :id")
                  .param("now", SqlTime.utc(now))
                  .param("id", r.id())
                  .update();
              // BR-14: registrations need reselection; still unselected at the lock, they are
              // cancelled by ForecastService.cancelUnselectedAtLock (reselect-deadline job).
              List<String> employees = registrants(r.id(), "status = 'ACTIVE'");
              flagged[0] +=
                  jdbc.sql(
                          """
                          UPDATE "Registration" SET status = 'NEEDS_RESELECT', "updatedAt" = :now
                          WHERE "menuItemId" = :id AND status = 'ACTIVE'
                          """)
                      .param("now", SqlTime.utc(now))
                      .param("id", r.id())
                      .update();
              if (!employees.isEmpty()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("date", day.date().toString());
                payload.put("dish", displayName(dishes, r.dishId()));
                // The deadline comes from the day.lockTime setting, never a fixed hour (rule 3).
                payload.put("lockTime", lockTime.toString());
                notices.add(new Notice(employees, "MENU_DISH_REMOVED", payload));
              }
            }

            // Desserts are display-only (BR-11); removed rows are kept with removedAt.
            List<MenuStore.Dessert> storedDesserts = stored == null ? List.of() : stored.desserts();
            for (MenuStore.Dessert row : storedDesserts) {
              if (!day.dessertIds().contains(row.dessertId())) {
                jdbc.sql("UPDATE \"MenuDayDessert\" SET \"removedAt\" = :now WHERE id = :id")
                    .param("now", SqlTime.utc(now))
                    .param("id", row.rowId())
                    .update();
              }
            }
            for (int sortOrder = 0; sortOrder < day.dessertIds().size(); sortOrder++) {
              String dessertId = day.dessertIds().get(sortOrder);
              MenuStore.Dessert row =
                  storedDesserts.stream()
                      .filter(x -> x.dessertId().equals(dessertId))
                      .findFirst()
                      .orElse(null);
              if (row == null) {
                jdbc.sql(
                        """
                        INSERT INTO "MenuDayDessert" (id, "menuDayId", "dessertId", "sortOrder")
                        VALUES (:id, :dayId, :dessertId, :sortOrder)
                        """)
                    .param("id", Ids.newId(now.toEpochMilli()))
                    .param("dayId", menuDayId)
                    .param("dessertId", dessertId)
                    .param("sortOrder", sortOrder)
                    .update();
              } else if (row.sortOrder() != sortOrder) {
                jdbc.sql("UPDATE \"MenuDayDessert\" SET \"sortOrder\" = :sortOrder WHERE id = :id")
                    .param("sortOrder", sortOrder)
                    .param("id", row.rowId())
                    .update();
              }
            }

            before.put(
                day.date().toString(),
                stored == null
                    ? null
                    : dayAudit(
                        stored.isServing(),
                        currentItems.stream().map(MenuRules.ItemRef::dishId).toList(),
                        currentDesserts));
            after.put(
                day.date().toString(),
                dayAudit(
                    day.isServing(),
                    day.items().stream().map(ItemInput::dishId).toList(),
                    day.dessertIds()));
          }

          if (!gaps.isEmpty()) {
            throw incomplete(gaps);
          }

          Set<String> recipients = new LinkedHashSet<>();
          for (Notice n : notices) {
            Map<String, Object> payload = new LinkedHashMap<>(n.payload());
            payload.put("weekStart", input.weekStart().toString());
            payload.put("canteen", canteen.name());
            String payloadJson = json.writeValueAsString(payload);
            for (String employeeId : n.employeeIds()) {
              jdbc.sql(
                      """
                      INSERT INTO "Notification" (id, "employeeId", type, payload, "createdAt")
                      VALUES (:id, :employeeId, CAST(:type AS "NotificationType"),
                          CAST(:payload AS jsonb), :now)
                      """)
                  .param("id", Ids.newId(now.toEpochMilli()))
                  .param("employeeId", employeeId)
                  .param("type", n.type())
                  .param("payload", payloadJson)
                  .param("now", SqlTime.utc(now))
                  .update();
            }
            recipients.addAll(n.employeeIds());
          }
          notified[0] = recipients.size();

          if (!after.isEmpty()) {
            Map<String, Object> afterWithEffects = new LinkedHashMap<>();
            afterWithEffects.put("days", after);
            afterWithEffects.put("effects", new Effects(moved[0], flagged[0], notified[0]));
            audit.log(
                AuditService.Entry.of(
                        actorId, published ? "menu.edited" : "menu.saved", "MenuWeek", week.id())
                    .withBefore(before)
                    .withAfter(afterWithEffects)
                    .withReason(input.reason()));
          }
        });

    return new MenuSaveResult(
        adminWeek(factoryId, canteen.id(), input.weekStart()),
        new Effects(moved[0], flagged[0], notified[0]));
  }

  private static Map<String, Object> dayAudit(
      boolean isServing, List<String> dishIds, List<String> dessertIds) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("isServing", isServing);
    m.put("dishIds", dishIds);
    m.put("dessertIds", dessertIds);
    return m;
  }

  /** The legacy error shape: {@code {code, itemId}} or {@code {code, dishId}}. */
  private static List<Map<String, String>> editErrors(List<MenuRules.EditError> errors) {
    return errors.stream()
        .map(
            e ->
                Map.of(
                    "code",
                    e.code().name(),
                    e.code() == MenuRules.EditErrorCode.DUPLICATE_DISH ? "dishId" : "itemId",
                    e.ref()))
        .toList();
  }

  private String insertItem(String menuDayId, String dishId, int sortOrder, Instant now) {
    String id = Ids.newId(now.toEpochMilli());
    jdbc.sql(
            """
            INSERT INTO "MenuItem" (id, "menuDayId", "dishId", "sortOrder")
            VALUES (:id, :dayId, :dishId, :sortOrder)
            """)
        .param("id", id)
        .param("dayId", menuDayId)
        .param("dishId", dishId)
        .param("sortOrder", sortOrder)
        .update();
    return id;
  }

  private List<String> registrants(String menuItemId, String statusFilter) {
    return jdbc.sql(
            "SELECT DISTINCT \"employeeId\" FROM \"Registration\" WHERE \"menuItemId\" = :id AND "
                + statusFilter)
        .param("id", menuItemId)
        .query(String.class)
        .list();
  }

  private static String displayName(Map<String, DishRow> dishes, String dishId) {
    DishRow dish = dishes.get(dishId);
    return dish == null ? dishId : MenuRules.mealDisplayName(dish.typeName(), dish.name());
  }

  /** The dishes the request names plus those already on the week (for BR-14 notice names). */
  private Map<String, DishRow> loadDishes(String factoryId, WeekInput input, MenuStore.Week week) {
    Set<String> ids = new LinkedHashSet<>();
    input.days().forEach(d -> d.items().forEach(i -> ids.add(i.dishId())));
    week.days().forEach(d -> d.items().forEach(i -> ids.add(i.dishId())));
    Map<String, DishRow> byId = new HashMap<>();
    if (ids.isEmpty()) {
      return byId;
    }
    jdbc.sql(
            """
            SELECT d.id, d."canteenId", d.name, t.name AS "typeName",
                   d."archivedAt" IS NOT NULL AS archived, 'LUNCH' = ANY(d.sessions) AS lunch
            FROM "Dish" d JOIN "DishType" t ON t.id = d."typeId"
            WHERE d."factoryId" = :factoryId AND d.id IN (:ids)
            """)
        .param("factoryId", factoryId)
        .param("ids", List.copyOf(ids))
        .query(DishRow.class)
        .list()
        .forEach(d -> byId.put(d.id(), d));
    return byId;
  }

  private Map<String, DessertRow> loadDesserts(String factoryId, WeekInput input) {
    Set<String> ids = new LinkedHashSet<>();
    input.days().forEach(d -> ids.addAll(d.dessertIds()));
    Map<String, DessertRow> byId = new HashMap<>();
    if (ids.isEmpty()) {
      return byId;
    }
    jdbc.sql(
            """
            SELECT id, "canteenId", "archivedAt" IS NOT NULL AS archived FROM "Dessert"
            WHERE "factoryId" = :factoryId AND id IN (:ids)
            """)
        .param("factoryId", factoryId)
        .param("ids", List.copyOf(ids))
        .query(DessertRow.class)
        .list()
        .forEach(d -> byId.put(d.id(), d));
    return byId;
  }

  /** BR-02 / BR-03 / BR-04: publishing opens registration and fixes closeAt. Audited. */
  public MenuWeekView publish(String actorId, String factoryId, String weekId) {
    MenuStore.Week week = store.byId(weekId);
    if (week == null || !week.factoryId().equals(factoryId)) {
      throw ApiException.notFound("Menu week not found");
    }
    if (week.published()) {
      throw ApiException.invalidState("Menu week is already published", null);
    }
    List<DayGaps> gaps =
        MenuRules.menuWeekGaps(
            week.days().stream()
                .map(
                    d ->
                        new DayCounts(
                            d.date(), d.isServing(), d.items().size(), d.desserts().size()))
                .toList());
    if (!gaps.isEmpty()) {
      throw incomplete(gaps);
    }
    Instant now = clock.now();
    Deadlines.CloseAt result =
        Deadlines.computeCloseAt(
            week.weekStart(),
            now,
            settings.getWeekdayTime(factoryId, SettingKey.REGISTRATION_CLOSE),
            settings.getInt(factoryId, SettingKey.REGISTRATION_LATE_PUBLISH_HOURS));
    tx.executeWithoutResult(
        status -> {
          // Conditional update: two concurrent publishes cannot both succeed.
          int updated =
              jdbc.sql(
                      """
                      UPDATE "MenuWeek" SET status = 'PUBLISHED', "publishedAt" = :now,
                          "publishedById" = :actorId, "closeAt" = :closeAt, "updatedAt" = :now
                      WHERE id = :id AND status = 'DRAFT'
                      """)
                  .param("now", SqlTime.utc(now))
                  .param("actorId", actorId)
                  .param("closeAt", SqlTime.utc(result.closeAt()))
                  .param("id", weekId)
                  .update();
          if (updated == 0) {
            throw ApiException.invalidState("Menu week is already published", null);
          }
          Map<String, Object> after = new LinkedHashMap<>();
          after.put("canteenId", week.canteenId());
          after.put("weekStart", week.weekStart().toString());
          after.put("closeAt", result.closeAt());
          after.put("latePublish", result.latePublish());
          audit.log(
              AuditService.Entry.of(actorId, "menu.published", "MenuWeek", weekId)
                  .withAfter(after));
        });
    return adminWeek(factoryId, week.canteenId(), week.weekStart());
  }

  /**
   * Publishes straight from the builder, without a separate "save draft" step (product owner,
   * 2026-10-23): the days are saved, then the week is published. Every serving day must have a main
   * dish and a dessert; that is checked on the submitted days before anything is written, and again
   * by {@link #publish} on the stored week.
   */
  public MenuWeekView saveAndPublish(String actorId, String factoryId, WeekInput input) {
    List<DayGaps> gaps =
        MenuRules.menuWeekGaps(
            input.days().stream()
                .map(
                    d ->
                        new DayCounts(
                            d.date(), d.isServing(), d.items().size(), d.dessertIds().size()))
                .toList());
    if (!gaps.isEmpty()) {
      throw incomplete(gaps);
    }
    MenuSaveResult saved = save(actorId, factoryId, input);
    if (!Objects.equals(saved.menu().status(), "DRAFT") || saved.menu().id() == null) {
      throw ApiException.invalidState("Menu week is already published", null);
    }
    return publish(actorId, factoryId, saved.menu().id());
  }

  /** What an employee sees: only a published menu of their own meal unit's canteen. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record MyMenuWeekView(
      String state, LocalDate weekStart, String canteenName, MenuWeekView menu) {}

  private record EmployeeRow(String orgUnitId, String floorOverrideId) {}

  /**
   * BR-02 / BR-15 / BR-63: an employee sees the published menu of their meal unit's canteen and
   * never a draft. Employees on the "unassigned" list see no menu.
   */
  public MyMenuWeekView myWeek(String employeeId, String factoryId, LocalDate weekStart) {
    assertMonday(weekStart);
    EmployeeRow employee =
        jdbc.sql("SELECT \"orgUnitId\", \"floorOverrideId\" FROM \"Employee\" WHERE id = :id")
            .param("id", employeeId)
            .query(EmployeeRow.class)
            .single();
    EmployeeMealAssignment assignment =
        Mapping.resolveEmployeeMealAssignment(
            employee.orgUnitId(), employee.floorOverrideId(), null, org.load(factoryId).index());
    if (!assignment.canRegister() || assignment.canteenId() == null) {
      return new MyMenuWeekView("UNASSIGNED", weekStart, null, null);
    }
    CanteenRow canteen =
        jdbc.sql("SELECT id, \"factoryId\", name, active FROM \"Canteen\" WHERE id = :id")
            .param("id", assignment.canteenId())
            .query(CanteenRow.class)
            .single();
    MenuStore.Week week = store.find(canteen.id(), weekStart);
    if (week == null || !week.published()) {
      return new MyMenuWeekView("NOT_PUBLISHED", weekStart, canteen.name(), null);
    }
    MenuWeekView menu = view(factoryId, canteen, weekStart, week, false);
    return new MyMenuWeekView("PUBLISHED", null, null, menu.servingDaysOnly());
  }

  /** BR-13: each dish type's usual bounds per serving day (inactive types are not checked). */
  private List<MixRule> mixRules(String factoryId) {
    return jdbc.sql(
            """
            SELECT id AS "typeId", name AS "typeName", "minPerDay", "maxPerDay" FROM "DishType"
            WHERE "factoryId" = :factoryId AND active ORDER BY "sortOrder", name
            """)
        .param("factoryId", factoryId)
        .query(MixRule.class)
        .list();
  }
}
