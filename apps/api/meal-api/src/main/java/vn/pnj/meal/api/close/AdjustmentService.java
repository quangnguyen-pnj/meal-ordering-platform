package vn.pnj.meal.api.close;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.close.CloseViews.AdjustmentCreated;
import vn.pnj.meal.api.close.CloseViews.AdjustmentLineView;
import vn.pnj.meal.api.close.CloseViews.AdjustmentView;
import vn.pnj.meal.api.close.CloseViews.AdjustmentsView;
import vn.pnj.meal.api.close.CloseViews.EmployeeDayMeal;
import vn.pnj.meal.api.close.CloseViews.EmployeeDayView;
import vn.pnj.meal.api.common.ApiErrorCode;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.db.SqlTime;
import vn.pnj.meal.api.org.OrgDirectory;
import vn.pnj.meal.api.org.OrgSnapshot;
import vn.pnj.meal.rules.close.Adjustments;
import vn.pnj.meal.rules.close.CountGrid;
import vn.pnj.meal.rules.close.DayCloseRules;
import vn.pnj.meal.rules.close.DayCloseRules.AdjustmentWindow;
import vn.pnj.meal.rules.org.Mapping;
import vn.pnj.meal.rules.org.Mapping.EmployeeMealAssignment;

/**
 * BR-46: adjustments of a finalized day by the HC admin, until {@code close.adjustmentDeadline}.
 * FinalCount never changes; each adjustment adds signed lines (effective count = FinalCount + sum
 * of lines) with the mapping in effect when written (BR-64), a required reason, the caterer's
 * confirmation when meals are added, an audit entry (BR-70) and a supplementary file.
 */
@Service
public class AdjustmentService {
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final ClockService clock;
  private final AuditService audit;
  private final OrgDirectory org;
  private final DayCloseStore days;
  private final CloseData data;

  public AdjustmentService(
      JdbcClient jdbc,
      TransactionTemplate tx,
      ClockService clock,
      AuditService audit,
      OrgDirectory org,
      DayCloseStore days,
      CloseData data) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.clock = clock;
    this.audit = audit;
    this.org = org;
    this.days = days;
    this.data = data;
  }

  private record EmployeeRow(
      String id,
      String employeeCode,
      String name,
      String orgUnitId,
      String floorOverrideId,
      String dmpCostCenterCode) {}

  private record Person(EmployeeRow row, EmployeeMealAssignment assignment) {}

  private Map<String, Person> people(String factoryId, List<String> codes) {
    Map<String, Person> out = new LinkedHashMap<>();
    if (codes.isEmpty()) {
      return out;
    }
    OrgSnapshot snapshot = org.load(factoryId);
    Map<String, String> ccByCode = org.costCenterIdsByCode(factoryId);
    jdbc.sql(
            """
            SELECT e.id, e."employeeCode", e.name, e."orgUnitId", e."floorOverrideId",
                   e."dmpCostCenterCode"
            FROM "Employee" e JOIN "OrgUnit" o ON o.id = e."orgUnitId"
            WHERE o."factoryId" = :factoryId AND e."employeeCode" IN (:codes)
            """)
        .param("factoryId", factoryId)
        .param("codes", codes)
        .query(EmployeeRow.class)
        .list()
        .forEach(
            e ->
                out.put(
                    e.employeeCode(),
                    new Person(
                        e,
                        Mapping.resolveEmployeeMealAssignment(
                            e.orgUnitId(),
                            e.floorOverrideId(),
                            e.dmpCostCenterCode() == null
                                ? null
                                : ccByCode.get(e.dmpCostCenterCode()),
                            snapshot.index()))));
    return out;
  }

  /** Day D's menu items by canteen. */
  private Map<String, Set<String>> menuByCanteen(String factoryId, LocalDate date) {
    Map<String, Set<String>> out = new HashMap<>();
    data.dayItems(factoryId, date)
        .forEach(i -> out.computeIfAbsent(i.canteenId(), k -> new LinkedHashSet<>()).add(i.id()));
    return out;
  }

  private DayCloseStore.Closed requireOpen(String factoryId, LocalDate date) {
    DayCloseStore.Closed closed = days.closed(factoryId, date);
    // TODO(BR-52): the final delivery confirmation arrives in P5; until then nothing is delivered.
    AdjustmentWindow window =
        DayCloseRules.adjustmentWindow(
            clock.now(), date, closed != null, days.adjustmentDeadline(factoryId), false);
    if (window != AdjustmentWindow.OPEN) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          ApiErrorCode.ADJUSTMENT_CLOSED,
          "Adjustments are accepted for a finalized day until close.adjustmentDeadline (BR-46)",
          Map.of("date", date.toString(), "window", window.name()),
          null);
    }
    return closed;
  }

  /** BR-46: records one adjustment. */
  public AdjustmentCreated create(
      String actorId,
      String factoryId,
      LocalDate date,
      List<Adjustments.Line> lines,
      @Nullable String reason,
      boolean catererConfirmed) {
    DayCloseStore.Closed closed = requireOpen(factoryId, date);
    List<String> codes = lines.stream().map(Adjustments.Line::employeeCode).distinct().toList();
    Map<String, Person> people = people(factoryId, codes);
    Map<String, Set<String>> menus = menuByCanteen(factoryId, date);
    Map<String, Set<String>> menuByEmployee = new HashMap<>();
    people.forEach(
        (code, p) ->
            menuByEmployee.put(
                code,
                p.assignment().canRegister() && p.assignment().canteenId() != null
                    ? menus.getOrDefault(p.assignment().canteenId(), Set.of())
                    : Set.of()));
    String cleanReason = reason == null ? null : reason.strip();
    return tx.execute(
        status -> {
          days.lockDay(factoryId, date);
          requireOpen(factoryId, date);
          Map<String, Map<String, Integer>> effective = new HashMap<>();
          Map<String, String> codeById = new HashMap<>();
          people.forEach((code, p) -> codeById.put(p.row().id(), code));
          data.employeeLines(closed.id(), people.values().stream().map(p -> p.row().id()).toList())
              .forEach(
                  (employeeId, byItem) ->
                      byItem.forEach(
                          (itemId, q) ->
                              effective
                                  .computeIfAbsent(codeById.get(employeeId), k -> new HashMap<>())
                                  .put(itemId, q[0] + q[1])));
          List<Adjustments.Error> errors =
              Adjustments.validate(lines, cleanReason, catererConfirmed, menuByEmployee, effective);
          if (!errors.isEmpty()) {
            throw ApiException.invalid(
                "Invalid adjustment (BR-46)",
                errors.stream()
                    .map(
                        e -> {
                          Map<String, Object> m = new LinkedHashMap<>();
                          m.put("code", e.code().name());
                          if (e.line() != null) {
                            m.put("line", e.line());
                          }
                          return m;
                        })
                    .toList());
          }
          Instant now = clock.now();
          String id = Ids.newId(now.toEpochMilli());
          jdbc.sql(
                  """
                  INSERT INTO "Adjustment" (id, "dayCloseId", number, "createdById", "createdAt",
                      reason, "catererConfirmed")
                  VALUES (:id, :dayCloseId,
                      (SELECT COALESCE(MAX(number), 0) + 1 FROM "Adjustment"
                       WHERE "dayCloseId" = :dayCloseId),
                      :actorId, :now, :reason, :caterer)
                  """)
              .param("id", id)
              .param("dayCloseId", closed.id())
              .param("actorId", actorId)
              .param("now", SqlTime.utc(now))
              .param("reason", cleanReason)
              .param("caterer", catererConfirmed)
              .update();
          Map<String, CloseData.Item> items =
              data.items(lines.stream().map(Adjustments.Line::menuItemId).distinct().toList());
          List<Map<String, Object>> audited = new ArrayList<>();
          int total = 0;
          for (Adjustments.Line l : lines) {
            Person p = people.get(l.employeeCode());
            EmployeeMealAssignment a = p.assignment();
            int delta = (int) l.delta();
            total += delta;
            jdbc.sql(
                    """
                    INSERT INTO "AdjustmentLine" (id, "adjustmentId", "employeeId", "menuItemId",
                        delta, "mealUnitId", "floorId", "canteenId", "costCenterId")
                    VALUES (:id, :adjustmentId, :employeeId, :menuItemId, :delta, :mealUnitId,
                        :floorId, :canteenId, :costCenterId)
                    """)
                .param("id", Ids.newId(now.toEpochMilli()))
                .param("adjustmentId", id)
                .param("employeeId", p.row().id())
                .param("menuItemId", l.menuItemId())
                .param("delta", delta)
                .param("mealUnitId", a.mealUnitId())
                .param("floorId", a.floorId())
                .param("canteenId", items.get(l.menuItemId()).canteenId())
                .param("costCenterId", a.costCenterId())
                .update();
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("employeeCode", l.employeeCode());
            line.put("menuItemId", l.menuItemId());
            line.put("delta", delta);
            audited.add(line);
          }
          Map<String, Object> after = new LinkedHashMap<>();
          after.put("date", date.toString());
          after.put("catererConfirmed", catererConfirmed);
          after.put("totalDelta", total);
          after.put("lines", audited);
          audit.log(
              AuditService.Entry.of(actorId, "adjustment.created", "Adjustment", id)
                  .withAfter(after)
                  .withReason(cleanReason));
          return new AdjustmentCreated(id, numberOf(id), total);
        });
  }

  private int numberOf(String adjustmentId) {
    return jdbc.sql("SELECT number FROM \"Adjustment\" WHERE id = :id")
        .param("id", adjustmentId)
        .query(Integer.class)
        .single();
  }

  private record Head(
      String id,
      int number,
      LocalDate date,
      Instant createdAt,
      String createdByName,
      String reason,
      boolean catererConfirmed) {}

  private List<Head> heads(String factoryId, @Nullable LocalDate date, @Nullable String id) {
    return jdbc.sql(
            """
            SELECT a.id, a.number, d.date, a."createdAt", e.name AS "createdByName",
                   a.reason, a."catererConfirmed"
            FROM "Adjustment" a
            JOIN "DayClose" d ON d.id = a."dayCloseId"
            JOIN "Employee" e ON e.id = a."createdById"
            WHERE d."factoryId" = :factoryId
              AND (CAST(:date AS date) IS NULL OR d.date = :date)
              AND (CAST(:id AS text) IS NULL OR a.id = :id)
            ORDER BY a.number
            """)
        .param("factoryId", factoryId)
        .param("date", date)
        .param("id", id)
        .query(
            (rs, i) ->
                new Head(
                    rs.getString("id"),
                    rs.getInt("number"),
                    rs.getObject("date", LocalDate.class),
                    SqlTime.instant(rs, "createdAt"),
                    rs.getString("createdByName"),
                    rs.getString("reason"),
                    rs.getBoolean("catererConfirmed")))
        .list();
  }

  /** BR-46: the adjustments of day D, oldest first, numbered from 1. */
  public AdjustmentsView list(String factoryId, LocalDate date) {
    DayCloseStore.Closed closed = days.closed(factoryId, date);
    List<Head> heads = heads(factoryId, date, null);
    Map<String, List<AdjustmentLineView>> lines = new HashMap<>();
    if (!heads.isEmpty()) {
      List<String> ids = heads.stream().map(Head::id).toList();
      List<String[]> raw = new ArrayList<>();
      jdbc.sql(
              """
              SELECT l."adjustmentId", e."employeeCode", e.name, l."menuItemId", l.delta,
                     u.name AS "unitName"
              FROM "AdjustmentLine" l
              JOIN "Employee" e ON e.id = l."employeeId"
              LEFT JOIN "OrgUnit" u ON u.id = l."mealUnitId"
              WHERE l."adjustmentId" IN (:ids)
              ORDER BY e."employeeCode", l.id
              """)
          .param("ids", ids)
          .query(
              (rs, i) ->
                  raw.add(
                      new String[] {
                        rs.getString("adjustmentId"),
                        rs.getString("employeeCode"),
                        rs.getString("name"),
                        rs.getString("menuItemId"),
                        Integer.toString(rs.getInt("delta")),
                        rs.getString("unitName")
                      }))
          .list();
      Map<String, CloseData.Item> items =
          data.items(raw.stream().map(r -> r[3]).distinct().toList());
      for (String[] r : raw) {
        CloseData.Item item = items.get(r[3]);
        lines
            .computeIfAbsent(r[0], k -> new ArrayList<>())
            .add(
                new AdjustmentLineView(
                    r[1],
                    r[2],
                    r[3],
                    item == null ? r[3] : item.displayName(),
                    Integer.parseInt(r[4]),
                    r[5]));
      }
    }
    List<AdjustmentView> items = new ArrayList<>();
    for (int i = 0; i < heads.size(); i++) {
      Head h = heads.get(i);
      List<AdjustmentLineView> mine = lines.getOrDefault(h.id(), List.of());
      items.add(
          new AdjustmentView(
              h.id(),
              h.number(),
              h.createdAt(),
              h.createdByName(),
              h.reason(),
              h.catererConfirmed(),
              mine.stream().mapToInt(AdjustmentLineView::delta).sum(),
              List.copyOf(mine)));
    }
    Instant deadline = DayCloseRules.adjustmentDeadlineAt(date, days.adjustmentDeadline(factoryId));
    return new AdjustmentsView(
        date,
        ClosedView.of(closed),
        DayCloseRules.adjustmentWindow(
                clock.now(), date, closed != null, days.adjustmentDeadline(factoryId), false)
            == AdjustmentWindow.OPEN,
        deadline,
        List.copyOf(items));
  }

  /** The adjustment form: one employee's final and effective meals of day D. */
  public EmployeeDayView employeeDay(String factoryId, String employeeCode, LocalDate date) {
    DayCloseStore.Closed closed = days.closed(factoryId, date);
    if (closed == null) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          ApiErrorCode.ADJUSTMENT_CLOSED,
          "Day is not finalized (BR-43)",
          Map.of("date", date.toString(), "window", AdjustmentWindow.NOT_FINALIZED.name()),
          null);
    }
    Person p = people(factoryId, List.of(employeeCode)).get(employeeCode);
    if (p == null) {
      throw ApiException.notFound("Employee not found");
    }
    String canteenId = p.assignment().canRegister() ? p.assignment().canteenId() : null;
    Set<String> menu =
        canteenId == null
            ? Set.of()
            : menuByCanteen(factoryId, date).getOrDefault(canteenId, Set.of());
    Map<String, int[]> lines =
        data.employeeLines(closed.id(), List.of(p.row().id())).getOrDefault(p.row().id(), Map.of());
    Set<String> ids = new LinkedHashSet<>(menu);
    ids.addAll(lines.keySet());
    Map<String, CloseData.Item> items = data.items(ids);
    List<EmployeeDayMeal> meals = new ArrayList<>();
    for (String id : ids) {
      CloseData.Item item = items.get(id);
      int[] q = lines.getOrDefault(id, new int[2]);
      meals.add(
          new EmployeeDayMeal(
              id,
              item == null ? id : item.displayName(),
              menu.contains(id),
              q[0],
              q[1],
              q[0] + q[1]));
    }
    OrgSnapshot snapshot = org.load(factoryId);
    String canteenName =
        canteenId == null
            ? null
            : jdbc.sql("SELECT name FROM \"Canteen\" WHERE id = :id")
                .param("id", canteenId)
                .query(String.class)
                .optional()
                .orElse(null);
    return new EmployeeDayView(
        p.row().employeeCode(),
        p.row().name(),
        snapshot.pathName(p.row().orgUnitId()),
        canteenName,
        List.copyOf(meals));
  }

  public record AdjustmentFile(LocalDate date, int number, byte[] body) {}

  /** BR-46: the supplementary file of one adjustment of this factory (404 for any other). */
  public AdjustmentFile file(String factoryId, String adjustmentId) {
    Head head =
        heads(factoryId, null, adjustmentId).stream()
            .findFirst()
            .orElseThrow(() -> ApiException.notFound("Adjustment not found"));
    List<CountGrid.Cell> cells = data.adjustmentCells(head.id());
    CountGrid.Grid grid = CountGrid.build(data.columns(factoryId, head.date(), cells), cells, true);
    return new AdjustmentFile(
        head.date(),
        head.number(),
        CloseWorkbooks.adjustmentFile(
            head.date(), head.number(), head.createdAt(), head.reason(), grid));
  }
}
