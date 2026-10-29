package vn.pnj.meal.api.master;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.common.ApiErrorCode;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.db.Ids;

/**
 * BR-62: canteens, floors and cost centers are managed by the HC admin: add, edit, deactivate,
 * never delete. Codes are unique per factory and never change (imports and DMP refer to them).
 */
@Service
public class MasterService {
  /** The three kinds share one service, as in the legacy API; the audit names come from here. */
  public enum Kind {
    CANTEEN("canteen", "Canteen"),
    FLOOR("floor", "Floor"),
    COST_CENTER("costCenter", "CostCenter");

    final String auditName;
    final String table;

    Kind(String auditName, String table) {
      this.auditName = auditName;
      this.table = table;
    }
  }

  private final JdbcClient jdbc;
  private final AuditService audit;
  private final ClockService clock;

  public MasterService(JdbcClient jdbc, AuditService audit, ClockService clock) {
    this.jdbc = jdbc;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * @param sortOrder floors only
   * @param source cost centers only: DMP or MANUAL
   * @param usageCount org nodes (and, for floors, employees' pick-up floors) that use this item
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record MasterItemView(
      String id,
      String code,
      String name,
      boolean active,
      @Nullable Integer sortOrder,
      @Nullable @Schema(allowableValues = {"DMP", "MANUAL"}) String source,
      int usageCount) {}

  @Transactional(readOnly = true)
  public List<MasterItemView> list(Kind kind, String factoryId) {
    String sql =
        switch (kind) {
          case CANTEEN ->
              """
              SELECT c.id, c.code, c.name, c.active, NULL::int AS "sortOrder", NULL AS source,
                     (SELECT count(*) FROM "OrgUnit" o WHERE o."canteenId" = c.id)::int AS "usageCount"
              FROM "Canteen" c WHERE c."factoryId" = :factoryId ORDER BY c.code
              """;
          case FLOOR ->
              """
              SELECT f.id, f.code, f.name, f.active, f."sortOrder", NULL AS source,
                     ((SELECT count(*) FROM "OrgUnit" o WHERE o."floorId" = f.id)
                      + (SELECT count(*) FROM "Employee" e WHERE e."floorOverrideId" = f.id))::int
                       AS "usageCount"
              FROM "Floor" f WHERE f."factoryId" = :factoryId ORDER BY f."sortOrder", f.code
              """;
          case COST_CENTER ->
              """
              SELECT c.id, c.code, c.name, c.active, NULL::int AS "sortOrder", c.source::text,
                     (SELECT count(*) FROM "OrgUnit" o WHERE o."costCenterId" = c.id)::int
                       AS "usageCount"
              FROM "CostCenter" c WHERE c."factoryId" = :factoryId ORDER BY c.code
              """;
        };
    return jdbc.sql(sql).param("factoryId", factoryId).query(MasterItemView.class).list();
  }

  /**
   * @param sortOrder floors only
   */
  public record Input(String code, String name, Integer sortOrder) {}

  public record Created(String id) {}

  @Transactional
  public Created create(Kind kind, String actorId, String factoryId, Input input) {
    String id = Ids.newId(clock.now().toEpochMilli());
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("code", input.code());
    after.put("name", input.name());
    try {
      switch (kind) {
        case CANTEEN -> {
          // MVP: one caterer per factory; a new canteen is served by the factory's supplier.
          String supplierId = activeSupplierId(factoryId);
          if (supplierId == null) {
            throw ApiException.notFound("No active supplier for this factory");
          }
          jdbc.sql(
                  """
                  INSERT INTO "Canteen" (id, "factoryId", code, name, "supplierId")
                  VALUES (:id, :factoryId, :code, :name, :supplierId)
                  """)
              .param("id", id)
              .param("factoryId", factoryId)
              .param("code", input.code())
              .param("name", input.name())
              .param("supplierId", supplierId)
              .update();
        }
        case FLOOR -> {
          after.put("sortOrder", input.sortOrder());
          jdbc.sql(
                  """
                  INSERT INTO "Floor" (id, "factoryId", code, name, "sortOrder")
                  VALUES (:id, :factoryId, :code, :name, :sortOrder)
                  """)
              .param("id", id)
              .param("factoryId", factoryId)
              .param("code", input.code())
              .param("name", input.name())
              .param("sortOrder", input.sortOrder())
              .update();
        }
        case COST_CENTER ->
            jdbc.sql(
                    """
                    INSERT INTO "CostCenter" (id, "factoryId", code, name, source)
                    VALUES (:id, :factoryId, :code, :name, 'MANUAL')
                    """)
                .param("id", id)
                .param("factoryId", factoryId)
                .param("code", input.code())
                .param("name", input.name())
                .update();
      }
    } catch (DuplicateKeyException e) {
      throw ApiException.conflict(
          "Code already exists", List.of(Map.of("path", "code", "message", "duplicate")));
    }
    audit.log(
        AuditService.Entry.of(actorId, kind.auditName + ".created", kind.table, id)
            .withAfter(after));
    return new Created(id);
  }

  /** Fields that are present in the request; null means "leave as is". */
  public record Patch(String name, Integer sortOrder, Boolean active) {}

  private record Current(String factoryId, String name, int sortOrder, boolean active) {}

  /** Edits name / order / active. Deactivating is refused while org nodes or employees use it. */
  @Transactional
  public Created update(Kind kind, String actorId, String factoryId, String id, Patch patch) {
    Current current =
        jdbc.sql(
                "SELECT \"factoryId\", name, "
                    + (kind == Kind.FLOOR ? "\"sortOrder\"" : "0 AS \"sortOrder\"")
                    + ", active FROM \""
                    + kind.table
                    + "\" WHERE id = :id FOR UPDATE")
            .param("id", id)
            .query(Current.class)
            .optional()
            .filter(c -> c.factoryId().equals(factoryId))
            .orElseThrow(() -> ApiException.notFound("Not found"));

    if (Boolean.FALSE.equals(patch.active()) && current.active()) {
      // TODO(BR-62): the plan does not say what happens to nodes mapped to a deactivated item;
      // refusing keeps every mapping valid (open-questions Q14).
      int inUse = usage(kind, id);
      if (inUse > 0) {
        throw new ApiException(
            HttpStatus.CONFLICT,
            ApiErrorCode.IN_USE,
            "Still mapped",
            Map.of("usageCount", inUse),
            null);
      }
    }

    Map<String, Object> before = new LinkedHashMap<>();
    Map<String, Object> after = new LinkedHashMap<>();
    if (patch.name() != null && !patch.name().equals(current.name())) {
      before.put("name", current.name());
      after.put("name", patch.name());
    }
    if (kind == Kind.FLOOR
        && patch.sortOrder() != null
        && patch.sortOrder() != current.sortOrder()) {
      before.put("sortOrder", current.sortOrder());
      after.put("sortOrder", patch.sortOrder());
    }
    if (patch.active() != null && patch.active() != current.active()) {
      before.put("active", current.active());
      after.put("active", patch.active());
    }
    if (after.isEmpty()) {
      return new Created(id);
    }

    jdbc.sql(
            "UPDATE \""
                + kind.table
                + "\" SET name = COALESCE(:name, name), active = COALESCE(:active, active)"
                + (kind == Kind.FLOOR
                    ? ", \"sortOrder\" = COALESCE(:sortOrder, \"sortOrder\")"
                    : "")
                + " WHERE id = :id")
        .param("name", after.get("name"))
        .param("active", after.get("active"))
        .param("sortOrder", after.get("sortOrder"))
        .param("id", id)
        .update();
    audit.log(
        AuditService.Entry.of(actorId, kind.auditName + ".updated", kind.table, id)
            .withBefore(before)
            .withAfter(after));
    return new Created(id);
  }

  private int usage(Kind kind, String id) {
    String sql =
        switch (kind) {
          case CANTEEN ->
              """
              SELECT (SELECT count(*) FROM "OrgUnit" WHERE "canteenId" = :id)
                   + (SELECT count(*) FROM "MenuWeek" WHERE "canteenId" = :id AND status <> 'CLOSED')
              """;
          case FLOOR ->
              """
              SELECT (SELECT count(*) FROM "OrgUnit" WHERE "floorId" = :id)
                   + (SELECT count(*) FROM "Employee" WHERE "floorOverrideId" = :id)
              """;
          // Employee cost-center overrides are no longer read (cost center follows the org tree).
          case COST_CENTER -> "SELECT count(*) FROM \"OrgUnit\" WHERE \"costCenterId\" = :id";
        };
    return jdbc.sql(sql).param("id", id).query(Integer.class).single();
  }

  private String activeSupplierId(String factoryId) {
    return jdbc.sql(
            """
            SELECT id FROM "Supplier" WHERE "factoryId" = :factoryId AND active
            ORDER BY name LIMIT 1
            """)
        .param("factoryId", factoryId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  /** The caterer as JavaScript's Number() showed the decimal: 25000, not 25000.00. */
  public record SupplierView(String id, String name, Number unitPrice) {}

  private record SupplierRow(String id, String name, BigDecimal unitPrice) {}

  @Transactional(readOnly = true)
  public SupplierView supplier(String factoryId) {
    SupplierRow row =
        jdbc.sql(
                """
                SELECT id, name, "unitPrice" FROM "Supplier"
                WHERE "factoryId" = :factoryId AND active ORDER BY name LIMIT 1
                """)
            .param("factoryId", factoryId)
            .query(SupplierRow.class)
            .optional()
            .orElseThrow(() -> ApiException.notFound("No active supplier"));
    return new SupplierView(row.id(), row.name(), plainNumber(row.unitPrice()));
  }

  private static Number plainNumber(BigDecimal value) {
    BigDecimal stripped = value.stripTrailingZeros();
    return stripped.scale() <= 0 ? stripped.longValueExact() : stripped.doubleValue();
  }

  /** Plan section 3 "business settings: caterer, unit price". Audited. */
  @Transactional
  public SupplierView updateSupplier(
      String actorId, String factoryId, String name, BigDecimal unitPrice) {
    SupplierView current = supplier(factoryId);
    jdbc.sql("UPDATE \"Supplier\" SET name = :name, \"unitPrice\" = :price WHERE id = :id")
        .param("name", name)
        .param("price", unitPrice)
        .param("id", current.id())
        .update();
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("name", current.name());
    before.put("unitPrice", current.unitPrice());
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("name", name);
    after.put("unitPrice", plainNumber(unitPrice));
    audit.log(
        AuditService.Entry.of(actorId, "supplier.updated", "Supplier", current.id())
            .withBefore(before)
            .withAfter(after));
    return supplier(factoryId);
  }
}
