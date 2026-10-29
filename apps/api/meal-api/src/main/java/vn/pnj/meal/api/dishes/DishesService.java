package vn.pnj.meal.api.dishes;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.db.SqlTime;
import vn.pnj.meal.rules.menu.LibraryImport;
import vn.pnj.meal.rules.menu.MenuRules;

/**
 * BR-10: main-dish and dessert libraries, one per canteen, reused across weeks; and the dish types
 * they use (master data since 2026-10-23). Retired entries are archived and can be restored; they
 * are never deleted. Archived entries cannot be put on a new menu.
 */
@Service
public class DishesService {
  public enum Library {
    DISH("dish", "Dish"),
    DESSERT("dessert", "Dessert");

    final String label;
    final String table;

    Library(String label, String table) {
      this.label = label;
      this.table = table;
    }
  }

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final AuditService audit;
  private final ClockService clock;

  public DishesService(
      JdbcClient jdbc, TransactionTemplate tx, AuditService audit, ClockService clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.audit = audit;
    this.clock = clock;
  }

  // --- Dish types -------------------------------------------------------------------------

  /**
   * @param dishCount library dishes (archived included) of this type
   */
  public record DishTypeView(
      String id,
      String code,
      String name,
      String color,
      int sortOrder,
      @Nullable Integer minPerDay,
      @Nullable Integer maxPerDay,
      boolean active,
      int dishCount) {}

  public List<DishTypeView> listTypes(String factoryId) {
    return jdbc.sql(
            """
            SELECT t.id, t.code, t.name, t.color, t."sortOrder", t."minPerDay", t."maxPerDay",
                   t.active, (SELECT count(*) FROM "Dish" d WHERE d."typeId" = t.id)::int AS "dishCount"
            FROM "DishType" t WHERE t."factoryId" = :factoryId ORDER BY t."sortOrder", t.name
            """)
        .param("factoryId", factoryId)
        .query(DishTypeView.class)
        .list();
  }

  public record TypeInput(
      String code,
      String name,
      String color,
      int sortOrder,
      Integer minPerDay,
      Integer maxPerDay) {}

  public record Id(String id) {}

  public Id createType(String actorId, String factoryId, TypeInput input) {
    try {
      return tx.execute(
          status -> {
            String id = Ids.newId(clock.now().toEpochMilli());
            jdbc.sql(
                    """
                    INSERT INTO "DishType" (id, "factoryId", code, name, color, "sortOrder",
                        "minPerDay", "maxPerDay", "createdAt")
                    VALUES (:id, :factoryId, :code, :name, :color, :sortOrder, :min, :max, :now)
                    """)
                .param("id", id)
                .param("factoryId", factoryId)
                .param("code", input.code().toUpperCase(Locale.ROOT))
                .param("name", input.name())
                .param("color", input.color())
                .param("sortOrder", input.sortOrder())
                .param("min", input.minPerDay())
                .param("max", input.maxPerDay())
                .param("now", SqlTime.utc(clock.now()))
                .update();
            audit.log(
                AuditService.Entry.of(actorId, "dishType.created", "DishType", id)
                    .withAfter(input));
            return new Id(id);
          });
    } catch (DuplicateKeyException e) {
      throw ApiException.conflict(
          "Code already exists", List.of(Map.of("path", "code", "message", "duplicate")));
    }
  }

  /**
   * Fields present in the request; {@code set*} tells an explicit null bound from an absent one.
   */
  public record TypePatch(
      String name,
      String color,
      Integer sortOrder,
      boolean setMin,
      Integer minPerDay,
      boolean setMax,
      Integer maxPerDay,
      Boolean active) {}

  private record TypeRow(
      String factoryId,
      String name,
      String color,
      int sortOrder,
      Integer minPerDay,
      Integer maxPerDay,
      boolean active) {}

  /** Name, colour, order, usual bounds (BR-13) and active. Deactivated types leave the pickers. */
  public Id updateType(String actorId, String factoryId, String id, TypePatch patch) {
    return tx.execute(
        status -> {
          TypeRow current =
              jdbc.sql(
                      """
                      SELECT "factoryId", name, color, "sortOrder", "minPerDay", "maxPerDay", active
                      FROM "DishType" WHERE id = :id FOR UPDATE
                      """)
                  .param("id", id)
                  .query(TypeRow.class)
                  .optional()
                  .filter(t -> t.factoryId().equals(factoryId))
                  .orElseThrow(() -> ApiException.notFound("Dish type not found"));
          Integer min = patch.setMin() ? patch.minPerDay() : current.minPerDay();
          Integer max = patch.setMax() ? patch.maxPerDay() : current.maxPerDay();
          if (min != null && max != null && min > max) {
            throw ApiException.invalid(
                "maxPerDay must be at least minPerDay",
                List.of(Map.of("path", "maxPerDay", "message", "min")));
          }
          jdbc.sql(
                  """
                  UPDATE "DishType" SET name = :name, color = :color, "sortOrder" = :sortOrder,
                      "minPerDay" = :min, "maxPerDay" = :max, active = :active
                  WHERE id = :id
                  """)
              .param("name", patch.name() != null ? patch.name() : current.name())
              .param("color", patch.color() != null ? patch.color() : current.color())
              .param(
                  "sortOrder", patch.sortOrder() != null ? patch.sortOrder() : current.sortOrder())
              .param("min", min)
              .param("max", max)
              .param("active", patch.active() != null ? patch.active() : current.active())
              .param("id", id)
              .update();
          Map<String, Object> before = new LinkedHashMap<>();
          before.put("name", current.name());
          before.put("color", current.color());
          before.put("sortOrder", current.sortOrder());
          before.put("minPerDay", current.minPerDay());
          before.put("maxPerDay", current.maxPerDay());
          before.put("active", current.active());
          Map<String, Object> after = new LinkedHashMap<>();
          putIfSet(after, "name", patch.name());
          putIfSet(after, "color", patch.color());
          putIfSet(after, "sortOrder", patch.sortOrder());
          if (patch.setMin()) {
            after.put("minPerDay", patch.minPerDay());
          }
          if (patch.setMax()) {
            after.put("maxPerDay", patch.maxPerDay());
          }
          putIfSet(after, "active", patch.active());
          audit.log(
              AuditService.Entry.of(actorId, "dishType.updated", "DishType", id)
                  .withBefore(before)
                  .withAfter(after));
          return new Id(id);
        });
  }

  private static void putIfSet(Map<String, Object> map, String key, Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  // --- Libraries --------------------------------------------------------------------------

  /**
   * @param displayName BR-12
   */
  public record DishView(
      String id,
      String canteenId,
      String name,
      String typeId,
      DishTypeLabel type,
      @ArraySchema(schema = @Schema(allowableValues = "LUNCH")) List<String> sessions,
      String displayName,
      @Nullable Instant archivedAt) {}

  public record DessertView(
      String id, String canteenId, String name, @Nullable Instant archivedAt) {}

  private static final String DISH_SELECT =
      """
      SELECT d.id, d."canteenId", d.name, d."typeId", t.name AS "typeName", t.color,
             d.sessions::text[] AS sessions, d."archivedAt"
      FROM "Dish" d JOIN "DishType" t ON t.id = d."typeId"
      """;

  private static DishView dishView(ResultSet rs, int i) throws SQLException {
    Array sessions = rs.getArray("sessions");
    String typeName = rs.getString("typeName");
    String name = rs.getString("name");
    return new DishView(
        rs.getString("id"),
        rs.getString("canteenId"),
        name,
        rs.getString("typeId"),
        new DishTypeLabel(rs.getString("typeId"), typeName, rs.getString("color")),
        List.of((String[]) sessions.getArray()),
        MenuRules.mealDisplayName(typeName, name),
        SqlTime.instant(rs, "archivedAt"));
  }

  public List<DishView> listDishes(String factoryId, String canteenId, boolean includeArchived) {
    canteenOf(factoryId, canteenId);
    return jdbc.sql(
            DISH_SELECT
                + """
                WHERE d."factoryId" = :factoryId AND d."canteenId" = :canteenId
                  AND (:all OR d."archivedAt" IS NULL)
                ORDER BY t."sortOrder", d.name
                """)
        .param("factoryId", factoryId)
        .param("canteenId", canteenId)
        .param("all", includeArchived)
        .query(DishesService::dishView)
        .list();
  }

  public List<DessertView> listDesserts(
      String factoryId, String canteenId, boolean includeArchived) {
    canteenOf(factoryId, canteenId);
    return jdbc.sql(
            """
            SELECT id, "canteenId", name, "archivedAt" FROM "Dessert"
            WHERE "factoryId" = :factoryId AND "canteenId" = :canteenId
              AND (:all OR "archivedAt" IS NULL)
            ORDER BY name
            """)
        .param("factoryId", factoryId)
        .param("canteenId", canteenId)
        .param("all", includeArchived)
        .query(
            (rs, i) ->
                new DessertView(
                    rs.getString("id"),
                    rs.getString("canteenId"),
                    rs.getString("name"),
                    SqlTime.instant(rs, "archivedAt")))
        .list();
  }

  private DishView dish(String id) {
    return jdbc.sql(DISH_SELECT + " WHERE d.id = :id")
        .param("id", id)
        .query(DishesService::dishView)
        .single();
  }

  /**
   * @param canteenId create only; a dish stays in its canteen's library
   */
  public record DishInput(String canteenId, String name, String typeId, List<String> sessions) {}

  public DishView createDish(String actorId, String factoryId, DishInput input) {
    return writeOnce(
        Library.DISH,
        () -> {
          canteenOf(factoryId, input.canteenId());
          activeType(factoryId, input.typeId());
          assertNoDuplicateDish(input.canteenId(), input.typeId(), input.name(), null);
          String id = Ids.newId(clock.now().toEpochMilli());
          jdbc.sql(
                  """
                  INSERT INTO "Dish" (id, "factoryId", "canteenId", name, "typeId", sessions,
                      "createdAt")
                  VALUES (:id, :factoryId, :canteenId, :name, :typeId,
                      CAST(:sessions AS "MealSession"[]), :now)
                  """)
              .param("id", id)
              .param("factoryId", factoryId)
              .param("canteenId", input.canteenId())
              .param("name", input.name())
              .param("typeId", input.typeId())
              .param("sessions", pgArray(input.sessions()))
              .param("now", SqlTime.utc(clock.now()))
              .update();
          audit.log(AuditService.Entry.of(actorId, "dish.created", "Dish", id).withAfter(input));
          return dish(id);
        });
  }

  private record DishRow(
      String factoryId, String canteenId, String name, String typeId, String sessions) {}

  public DishView updateDish(String actorId, String factoryId, String id, DishInput input) {
    return writeOnce(
        Library.DISH,
        () -> {
          DishRow current =
              jdbc.sql(
                      """
                      SELECT "factoryId", "canteenId", name, "typeId", sessions::text AS sessions
                      FROM "Dish" WHERE id = :id FOR UPDATE
                      """)
                  .param("id", id)
                  .query(DishRow.class)
                  .optional()
                  .filter(d -> d.factoryId().equals(factoryId))
                  .orElseThrow(() -> ApiException.notFound("Dish not found"));
          if (!input.typeId().equals(current.typeId())) {
            activeType(factoryId, input.typeId());
          }
          assertNoDuplicateDish(current.canteenId(), input.typeId(), input.name(), id);
          jdbc.sql(
                  """
                  UPDATE "Dish" SET name = :name, "typeId" = :typeId,
                      sessions = CAST(:sessions AS "MealSession"[])
                  WHERE id = :id
                  """)
              .param("name", input.name())
              .param("typeId", input.typeId())
              .param("sessions", pgArray(input.sessions()))
              .param("id", id)
              .update();
          Map<String, Object> before = new LinkedHashMap<>();
          before.put("name", current.name());
          before.put("typeId", current.typeId());
          before.put("sessions", fromPgArray(current.sessions()));
          Map<String, Object> after = new LinkedHashMap<>();
          after.put("name", input.name());
          after.put("typeId", input.typeId());
          after.put("sessions", input.sessions());
          audit.log(
              AuditService.Entry.of(actorId, "dish.updated", "Dish", id)
                  .withBefore(before)
                  .withAfter(after));
          return dish(id);
        });
  }

  public record DessertInput(String canteenId, String name) {}

  public Id createDessert(String actorId, String factoryId, DessertInput input) {
    return writeOnce(
        Library.DESSERT,
        () -> {
          canteenOf(factoryId, input.canteenId());
          assertNoDuplicateDessert(input.canteenId(), input.name(), null);
          String id = Ids.newId(clock.now().toEpochMilli());
          jdbc.sql(
                  """
                  INSERT INTO "Dessert" (id, "factoryId", "canteenId", name, "createdAt")
                  VALUES (:id, :factoryId, :canteenId, :name, :now)
                  """)
              .param("id", id)
              .param("factoryId", factoryId)
              .param("canteenId", input.canteenId())
              .param("name", input.name())
              .param("now", SqlTime.utc(clock.now()))
              .update();
          audit.log(
              AuditService.Entry.of(actorId, "dessert.created", "Dessert", id).withAfter(input));
          return new Id(id);
        });
  }

  private record DessertRow(String factoryId, String canteenId, String name) {}

  public Id updateDessert(String actorId, String factoryId, String id, String name) {
    return writeOnce(
        Library.DESSERT,
        () -> {
          DessertRow current =
              jdbc.sql(
                      """
                      SELECT "factoryId", "canteenId", name FROM "Dessert" WHERE id = :id FOR UPDATE
                      """)
                  .param("id", id)
                  .query(DessertRow.class)
                  .optional()
                  .filter(d -> d.factoryId().equals(factoryId))
                  .orElseThrow(() -> ApiException.notFound("Dessert not found"));
          assertNoDuplicateDessert(current.canteenId(), name, id);
          jdbc.sql("UPDATE \"Dessert\" SET name = :name WHERE id = :id")
              .param("name", name)
              .param("id", id)
              .update();
          audit.log(
              AuditService.Entry.of(actorId, "dessert.updated", "Dessert", id)
                  .withBefore(Map.of("name", current.name()))
                  .withAfter(Map.of("name", name)));
          return new Id(id);
        });
  }

  public record Archived(String id, Instant archivedAt) {}

  private record ArchiveRow(
      String factoryId, String canteenId, String typeId, String name, Instant archivedAt) {}

  /**
   * Archive ({@code archived = true}) or restore. Menus already built keep the entry. Restoring is
   * refused when an active entry with the same name was added meanwhile.
   */
  public Archived setArchived(
      Library library, String actorId, String factoryId, String id, boolean archived) {
    return writeOnce(
        library,
        () -> {
          ArchiveRow current =
              jdbc.sql(
                      "SELECT \"factoryId\", \"canteenId\", "
                          + (library == Library.DISH ? "\"typeId\"" : "NULL AS \"typeId\"")
                          + ", name, \"archivedAt\" FROM \""
                          + library.table
                          + "\" WHERE id = :id FOR UPDATE")
                  .param("id", id)
                  .query(
                      (rs, i) ->
                          new ArchiveRow(
                              rs.getString("factoryId"),
                              rs.getString("canteenId"),
                              rs.getString("typeId"),
                              rs.getString("name"),
                              SqlTime.instant(rs, "archivedAt")))
                  .optional()
                  .filter(r -> r.factoryId().equals(factoryId))
                  .orElseThrow(() -> ApiException.notFound("Not found"));
          if ((current.archivedAt() != null) == archived) {
            return new Archived(id, current.archivedAt());
          }
          if (!archived) {
            if (library == Library.DISH) {
              assertNoDuplicateDish(current.canteenId(), current.typeId(), current.name(), id);
            } else {
              assertNoDuplicateDessert(current.canteenId(), current.name(), id);
            }
          }
          Instant archivedAt = archived ? clock.now() : null;
          jdbc.sql("UPDATE \"" + library.table + "\" SET \"archivedAt\" = :at WHERE id = :id")
              .param("at", SqlTime.utc(archivedAt))
              .param("id", id)
              .update();
          audit.log(
              AuditService.Entry.of(
                  actorId,
                  library.label + "." + (archived ? "archived" : "restored"),
                  library.table,
                  id));
          return new Archived(id, archivedAt);
        });
  }

  // --- Excel import -----------------------------------------------------------------------

  public record LibraryImportView(
      @Schema(allowableValues = {"PREVIEW", "APPLIED", "REJECTED"}) String status,
      int rowCount,
      List<LibraryImport.LibraryImportError> errors,
      List<LibraryImport.Row> toCreate,
      int alreadyInLibrary) {}

  public record TypeCode(String id, String code, String name) {}

  /** Active dish types, in picker order (template and import). */
  public List<TypeCode> activeTypeCodes(String factoryId) {
    return jdbc.sql(
            """
            SELECT id, code, name FROM "DishType" WHERE "factoryId" = :factoryId AND active
            ORDER BY "sortOrder", name
            """)
        .param("factoryId", factoryId)
        .query(TypeCode.class)
        .list();
  }

  /**
   * Imports a file into one canteen's library. {@code apply = false} only validates and previews.
   * One invalid row rejects the file; rows already in the library are skipped, so nothing is ever
   * added twice. Applying writes every new entry and one audit entry in one transaction.
   */
  public LibraryImportView importLibrary(
      String actorId,
      String factoryId,
      String canteenId,
      List<LibraryImport.RawRow> raw,
      boolean apply) {
    Supplier<LibraryImportView> run =
        () -> {
          CanteenRef canteen = canteenOf(factoryId, canteenId);
          if (apply && !canteen.active()) {
            throw ApiException.invalidState("Canteen is inactive", null);
          }
          List<TypeCode> types = activeTypeCodes(factoryId);
          Set<String> existingDishes = new HashSet<>();
          jdbc.sql(
                  """
                  SELECT t.code, d.name FROM "Dish" d JOIN "DishType" t ON t.id = d."typeId"
                  WHERE d."canteenId" = :canteenId
                  """)
              .param("canteenId", canteenId)
              .query(
                  (rs, i) ->
                      existingDishes.add(LibraryImport.dishKey(rs.getString(1), rs.getString(2))))
              .list();
          Set<String> existingDesserts = new HashSet<>();
          jdbc.sql("SELECT name FROM \"Dessert\" WHERE \"canteenId\" = :canteenId")
              .param("canteenId", canteenId)
              .query((rs, i) -> existingDesserts.add(MenuRules.libraryNameKey(rs.getString(1))))
              .list();
          LibraryImport.Result result =
              LibraryImport.validate(
                  raw,
                  new LibraryImport.Lookups(
                      new HashSet<>(types.stream().map(TypeCode::code).toList()),
                      existingDishes,
                      existingDesserts));
          if (!result.ok()) {
            return new LibraryImportView("REJECTED", raw.size(), result.errors(), List.of(), 0);
          }
          if (apply && !result.toCreate().isEmpty()) {
            Map<String, String> typeIds = new LinkedHashMap<>();
            types.forEach(t -> typeIds.put(t.code(), t.id()));
            Instant now = clock.now();
            for (LibraryImport.Row row : result.toCreate()) {
              if (row.kind() == LibraryImport.Kind.MAIN) {
                jdbc.sql(
                        """
                        INSERT INTO "Dish" (id, "factoryId", "canteenId", name, "typeId", sessions,
                            "createdAt")
                        VALUES (:id, :factoryId, :canteenId, :name, :typeId, '{LUNCH}', :now)
                        """)
                    .param("id", Ids.newId(now.toEpochMilli()))
                    .param("factoryId", factoryId)
                    .param("canteenId", canteenId)
                    .param("name", row.name())
                    .param("typeId", typeIds.get(row.typeCode()))
                    .param("now", SqlTime.utc(now))
                    .update();
              } else {
                jdbc.sql(
                        """
                        INSERT INTO "Dessert" (id, "factoryId", "canteenId", name, "createdAt")
                        VALUES (:id, :factoryId, :canteenId, :name, :now)
                        """)
                    .param("id", Ids.newId(now.toEpochMilli()))
                    .param("factoryId", factoryId)
                    .param("canteenId", canteenId)
                    .param("name", row.name())
                    .param("now", SqlTime.utc(now))
                    .update();
              }
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put(
                "created",
                result.toCreate().stream()
                    .map(
                        r -> {
                          Map<String, Object> m = new LinkedHashMap<>();
                          m.put("kind", r.kind().name());
                          m.put("type", r.typeCode());
                          m.put("name", r.name());
                          return m;
                        })
                    .toList());
            after.put("skipped", result.alreadyInLibrary().size());
            audit.log(
                AuditService.Entry.of(actorId, "dishLibrary.imported", "Canteen", canteenId)
                    .withAfter(after));
          }
          return new LibraryImportView(
              apply ? "APPLIED" : "PREVIEW",
              raw.size(),
              List.of(),
              result.toCreate(),
              result.alreadyInLibrary().size());
        };
    return apply ? writeOnce(Library.DISH, run) : run.get();
  }

  // --- Helpers ----------------------------------------------------------------------------

  record CanteenRef(String factoryId, boolean active) {}

  CanteenRef canteenOf(String factoryId, String canteenId) {
    return jdbc.sql("SELECT \"factoryId\", active FROM \"Canteen\" WHERE id = :id")
        .param("id", canteenId)
        .query(CanteenRef.class)
        .optional()
        .filter(c -> c.factoryId().equals(factoryId))
        .orElseThrow(() -> ApiException.notFound("Canteen not found"));
  }

  private record TypeRef(String factoryId, boolean active) {}

  private void activeType(String factoryId, String typeId) {
    boolean ok =
        jdbc.sql("SELECT \"factoryId\", active FROM \"DishType\" WHERE id = :id")
            .param("id", typeId)
            .query(TypeRef.class)
            .optional()
            .filter(t -> t.factoryId().equals(factoryId) && t.active())
            .isPresent();
    if (!ok) {
      throw ApiException.invalid(
          "Dish type not available", List.of(Map.of("path", "typeId", "message", typeId)));
    }
  }

  private record Existing(String id, String name, boolean archived) {}

  /** Same canteen, same type, same name ignoring case and spacing; archived entries count too. */
  private void assertNoDuplicateDish(
      String canteenId, String typeId, String name, String exceptId) {
    assertNoDuplicate(
        Library.DISH,
        jdbc.sql(
                """
                SELECT id, name, "archivedAt" IS NOT NULL AS archived FROM "Dish"
                WHERE "canteenId" = :canteenId AND "typeId" = :typeId AND lower(name) = lower(:name)
                """)
            .param("canteenId", canteenId)
            .param("typeId", typeId)
            .param("name", name)
            .query(Existing.class)
            .list(),
        name,
        exceptId);
  }

  private void assertNoDuplicateDessert(String canteenId, String name, String exceptId) {
    assertNoDuplicate(
        Library.DESSERT,
        jdbc.sql(
                """
                SELECT id, name, "archivedAt" IS NOT NULL AS archived FROM "Dessert"
                WHERE "canteenId" = :canteenId AND lower(name) = lower(:name)
                """)
            .param("canteenId", canteenId)
            .param("name", name)
            .query(Existing.class)
            .list(),
        name,
        exceptId);
  }

  /** The "added twice" bug: the same name (any case or spacing) in one canteen's library. */
  private static void assertNoDuplicate(
      Library library, List<Existing> rows, String name, String exceptId) {
    String key = MenuRules.libraryNameKey(name);
    rows.stream()
        .filter(r -> !r.id().equals(exceptId) && MenuRules.libraryNameKey(r.name()).equals(key))
        .findFirst()
        .ifPresent(
            same -> {
              Map<String, String> detail = new LinkedHashMap<>();
              detail.put("path", "name");
              detail.put("message", same.archived() ? "DUPLICATE_ARCHIVED" : "DUPLICATE");
              detail.put("id", same.id());
              throw ApiException.conflict(
                  "This " + library.label + " is already in the library", List.of(detail));
            });
  }

  /** Runs {@code write} in a transaction; two identical requests at once hit the unique index. */
  private <T> T writeOnce(Library library, Supplier<T> write) {
    try {
      return tx.execute(status -> write.get());
    } catch (DuplicateKeyException e) {
      throw ApiException.conflict(
          "This " + library.label + " is already in the library",
          List.of(Map.of("path", "name", "message", "DUPLICATE")));
    }
  }

  private static String pgArray(List<String> values) {
    return "{" + String.join(",", values) + "}";
  }

  private static List<String> fromPgArray(String literal) {
    String inner = literal.substring(1, literal.length() - 1);
    return inner.isEmpty() ? List.of() : Arrays.asList(inner.split(","));
  }
}
