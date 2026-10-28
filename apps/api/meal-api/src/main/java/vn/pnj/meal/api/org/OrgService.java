package vn.pnj.meal.api.org;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.db.SqlTime;
import vn.pnj.meal.api.employees.EmployeeMappingView;
import vn.pnj.meal.api.employees.EmployeesService;
import vn.pnj.meal.rules.org.Mapping;
import vn.pnj.meal.rules.org.Mapping.Field;
import vn.pnj.meal.rules.org.Mapping.MealStatus;
import vn.pnj.meal.rules.org.Mapping.Resolved;

/** /admin/org: the tree with its mapping, meal-unit flags, dept admins and the problem lists. */
@Service
public class OrgService {
  private final JdbcClient jdbc;
  private final OrgDirectory org;
  private final MealUnits mealUnits;
  private final EmployeesService employees;
  private final AuditService audit;
  private final ClockService clock;

  public OrgService(
      JdbcClient jdbc,
      OrgDirectory org,
      MealUnits mealUnits,
      EmployeesService employees,
      AuditService audit,
      ClockService clock) {
    this.jdbc = jdbc;
    this.org = org;
    this.mealUnits = mealUnits;
    this.employees = employees;
    this.audit = audit;
    this.clock = clock;
  }

  public record Own(String floorId, String canteenId, String costCenterId) {}

  /**
   * @param sourceNodeId node that supplies the value; differs from the node itself when inherited
   */
  public record ResolvedValueView(String id, String sourceNodeId, boolean inherited) {
    static ResolvedValueView of(Resolved r) {
      return new ResolvedValueView(r.value(), r.sourceNodeId(), r.inherited());
    }
  }

  public record ResolvedView(
      ResolvedValueView floor, ResolvedValueView canteen, ResolvedValueView costCenter) {}

  public record DeptAdminView(String assignmentId, String employeeCode, String name) {}

  /**
   * @param mealUnitId BR-60: the nearest meal unit at or above this node
   * @param mealUnitValid BR-63: only meaningful for meal units, null otherwise
   * @param directHeadcount active employees attached directly to this node
   */
  public record OrgUnitView(
      String id,
      String dmpCode,
      String name,
      int level,
      String dmpLevelName,
      String parentId,
      boolean active,
      boolean isMealUnit,
      Own own,
      ResolvedView resolved,
      String mealUnitId,
      Boolean mealUnitValid,
      int directHeadcount,
      List<DeptAdminView> deptAdmins) {}

  private record AdminRow(String id, String orgUnitId, String employeeCode, String name) {}

  /** The 6-level tree with own and inherited mapping (BR-61, BR-62) and dept admins. */
  @Transactional(readOnly = true)
  public List<OrgUnitView> tree(String factoryId) {
    OrgSnapshot snapshot = org.load(factoryId);
    Map<String, Integer> headcount = new HashMap<>();
    jdbc.sql(
            """
            SELECT e."orgUnitId", count(*)::int FROM "Employee" e
            JOIN "OrgUnit" o ON o.id = e."orgUnitId"
            WHERE e.status = 'ACTIVE' AND o."factoryId" = :factoryId GROUP BY e."orgUnitId"
            """)
        .param("factoryId", factoryId)
        .query((rs, i) -> headcount.put(rs.getString(1), rs.getInt(2)))
        .list();
    Map<String, List<DeptAdminView>> admins = new HashMap<>();
    jdbc.sql(
            """
            SELECT r.id, r."orgUnitId", e."employeeCode", e.name FROM "RoleAssignment" r
            JOIN "Employee" e ON e.id = r."employeeId"
            WHERE r."factoryId" = :factoryId AND r.role = 'DEPT_ADMIN' AND r."revokedAt" IS NULL
              AND r."orgUnitId" IS NOT NULL
            ORDER BY r."createdAt", r.id
            """)
        .param("factoryId", factoryId)
        .query(AdminRow.class)
        .list()
        .forEach(
            a ->
                admins
                    .computeIfAbsent(a.orgUnitId(), k -> new ArrayList<>())
                    .add(new DeptAdminView(a.id(), a.employeeCode(), a.name())));

    return snapshot.nodes().stream()
        .map(
            n ->
                new OrgUnitView(
                    n.id(),
                    n.dmpCode(),
                    n.name(),
                    n.level(),
                    n.dmpLevelName(),
                    n.parentId(),
                    n.active(),
                    n.isMealUnit(),
                    new Own(n.floorId(), n.canteenId(), n.costCenterId()),
                    new ResolvedView(
                        ResolvedValueView.of(
                            Mapping.resolve(n.id(), Field.FLOOR, snapshot.index())),
                        ResolvedValueView.of(
                            Mapping.resolve(n.id(), Field.CANTEEN, snapshot.index())),
                        ResolvedValueView.of(
                            Mapping.resolve(n.id(), Field.COST_CENTER, snapshot.index()))),
                    Mapping.resolveMealUnitId(n.id(), snapshot.index()),
                    n.isMealUnit()
                        ? Mapping.resolveMealUnitMapping(n.id(), snapshot.index()).valid()
                        : null,
                    headcount.getOrDefault(n.id(), 0),
                    admins.getOrDefault(n.id(), List.of())))
        .toList();
  }

  /**
   * The fields of an update that are present; for the ids, {@code set*} tells "clear it" (null)
   * apart from "leave it" (absent).
   */
  public record UnitPatch(
      Boolean isMealUnit,
      boolean setFloor,
      String floorId,
      boolean setCanteen,
      String canteenId,
      boolean setCostCenter,
      String costCenterId) {}

  public record UnitUpdated(boolean changed, int mealUnitsRecomputed) {}

  private record UnitRow(
      String factoryId,
      boolean isMealUnit,
      String floorId,
      String canteenId,
      String costCenterId) {}

  /** BR-60 / BR-62: meal-unit flag and own mapping of one node. Audited; meal units recomputed. */
  @Transactional
  public UnitUpdated updateUnit(String actorId, String factoryId, String unitId, UnitPatch patch) {
    UnitRow unit =
        jdbc.sql(
                """
                SELECT "factoryId", "isMealUnit", "floorId", "canteenId", "costCenterId"
                FROM "OrgUnit" WHERE id = :id FOR UPDATE
                """)
            .param("id", unitId)
            .query(UnitRow.class)
            .optional()
            .filter(u -> u.factoryId().equals(factoryId))
            .orElseThrow(() -> ApiException.notFound("Org unit not found"));
    assertMasterRef(factoryId, "floorId", "Floor", patch.floorId());
    assertMasterRef(factoryId, "canteenId", "Canteen", patch.canteenId());
    assertMasterRef(factoryId, "costCenterId", "CostCenter", patch.costCenterId());

    Map<String, Object> before = new LinkedHashMap<>();
    Map<String, Object> after = new LinkedHashMap<>();
    if (patch.isMealUnit() != null && patch.isMealUnit() != unit.isMealUnit()) {
      before.put("isMealUnit", unit.isMealUnit());
      after.put("isMealUnit", patch.isMealUnit());
    }
    diff(before, after, "floorId", patch.setFloor(), unit.floorId(), patch.floorId());
    diff(before, after, "canteenId", patch.setCanteen(), unit.canteenId(), patch.canteenId());
    diff(
        before,
        after,
        "costCenterId",
        patch.setCostCenter(),
        unit.costCenterId(),
        patch.costCenterId());
    if (after.isEmpty()) {
      return new UnitUpdated(false, 0);
    }

    boolean isMealUnit = (boolean) after.getOrDefault("isMealUnit", unit.isMealUnit());
    jdbc.sql(
            """
            UPDATE "OrgUnit" SET "isMealUnit" = :isMealUnit, "floorId" = :floorId,
                   "canteenId" = :canteenId, "costCenterId" = :costCenterId
            WHERE id = :id
            """)
        .param("isMealUnit", isMealUnit)
        .param("floorId", after.containsKey("floorId") ? patch.floorId() : unit.floorId())
        .param("canteenId", after.containsKey("canteenId") ? patch.canteenId() : unit.canteenId())
        .param(
            "costCenterId",
            after.containsKey("costCenterId") ? patch.costCenterId() : unit.costCenterId())
        .param("id", unitId)
        .update();
    audit.log(
        AuditService.Entry.of(actorId, "orgUnit.mapping.updated", "OrgUnit", unitId)
            .withBefore(before)
            .withAfter(after));
    int recomputed = after.containsKey("isMealUnit") ? mealUnits.recompute(factoryId) : 0;
    return new UnitUpdated(true, recomputed);
  }

  private static void diff(
      Map<String, Object> before,
      Map<String, Object> after,
      String field,
      boolean present,
      String current,
      String next) {
    if (present && !Objects.equals(current, next)) {
      before.put(field, current);
      after.put(field, next);
    }
  }

  private record MasterRef(String factoryId, boolean active) {}

  /** A referenced floor / canteen / cost center must exist in the factory and be active. */
  private void assertMasterRef(String factoryId, String field, String table, String id) {
    if (id == null) {
      return;
    }
    boolean ok =
        jdbc.sql("SELECT \"factoryId\", active FROM \"" + table + "\" WHERE id = :id")
            .param("id", id)
            .query(MasterRef.class)
            .optional()
            .filter(r -> r.factoryId().equals(factoryId) && r.active())
            .isPresent();
    if (!ok) {
      throw ApiException.invalid(
          "Unknown or inactive master data", List.of(Map.of("path", field, "message", "inactive")));
    }
  }

  /** BR-60 / BR-63: employees who cannot register (no meal unit, or an invalid one). */
  @Transactional(readOnly = true)
  public List<EmployeeMappingView> unassigned(String factoryId) {
    return employees.describeActive(factoryId).stream()
        .filter(e -> e.mealStatus() != MealStatus.OK)
        .toList();
  }

  /** BR-63: employees with no cost center (they can still register). */
  @Transactional(readOnly = true)
  public List<EmployeeMappingView> missingCostCenter(String factoryId) {
    return employees.describeActive(factoryId).stream()
        .filter(e -> e.costCenterId() == null)
        .toList();
  }

  public record Assigned(String id, boolean created) {}

  private record UnitRef(String factoryId, boolean active) {}

  private record EmployeeRef(String id, String status) {}

  /** Plan section 3: HC and system admins assign dept admins to org nodes (and their children). */
  @Transactional
  public Assigned assignDeptAdmin(
      String actorId, String factoryId, String unitId, String employeeCode) {
    UnitRef unit =
        jdbc.sql("SELECT \"factoryId\", active FROM \"OrgUnit\" WHERE id = :id")
            .param("id", unitId)
            .query(UnitRef.class)
            .optional()
            .filter(u -> u.factoryId().equals(factoryId))
            .orElseThrow(() -> ApiException.notFound("Org unit not found"));
    if (!unit.active()) {
      throw ApiException.invalidState("Org unit is no longer active", null);
    }
    EmployeeRef employee =
        jdbc.sql("SELECT id, status::text FROM \"Employee\" WHERE \"employeeCode\" = :code")
            .param("code", employeeCode)
            .query(EmployeeRef.class)
            .optional()
            .filter(e -> e.status().equals("ACTIVE"))
            .orElseThrow(
                () ->
                    ApiException.invalid(
                        "Unknown or inactive employee",
                        List.of(Map.of("path", "employeeCode", "message", "unknown"))));
    var existing =
        jdbc.sql(
                """
                SELECT id FROM "RoleAssignment" WHERE "employeeId" = :employeeId
                  AND role = 'DEPT_ADMIN' AND "orgUnitId" = :unitId AND "revokedAt" IS NULL
                LIMIT 1
                """)
            .param("employeeId", employee.id())
            .param("unitId", unitId)
            .query(String.class)
            .optional();
    if (existing.isPresent()) {
      return new Assigned(existing.get(), false);
    }
    var now = clock.now();
    String id = Ids.newId(now.toEpochMilli());
    jdbc.sql(
            """
            INSERT INTO "RoleAssignment" (id, "employeeId", role, "factoryId", "orgUnitId", "createdAt")
            VALUES (:id, :employeeId, 'DEPT_ADMIN', :factoryId, :unitId, :now)
            """)
        .param("id", id)
        .param("employeeId", employee.id())
        .param("factoryId", factoryId)
        .param("unitId", unitId)
        .param("now", SqlTime.utc(now))
        .update();
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("role", "DEPT_ADMIN");
    after.put("orgUnitId", unitId);
    after.put("assignmentId", id);
    audit.log(
        AuditService.Entry.of(actorId, "role.assigned", "Employee", employee.id())
            .withAfter(after));
    return new Assigned(id, true);
  }

  public record Revoked(boolean revoked) {}

  private record AssignmentRow(
      String factoryId, String role, String employeeId, String orgUnitId, boolean revoked) {}

  @Transactional
  public Revoked revokeDeptAdmin(String actorId, String factoryId, String assignmentId) {
    AssignmentRow row =
        jdbc.sql(
                """
                SELECT "factoryId", role::text, "employeeId", "orgUnitId",
                       "revokedAt" IS NOT NULL AS revoked
                FROM "RoleAssignment" WHERE id = :id FOR UPDATE
                """)
            .param("id", assignmentId)
            .query(AssignmentRow.class)
            .optional()
            .filter(r -> r.factoryId().equals(factoryId) && r.role().equals("DEPT_ADMIN"))
            .orElseThrow(() -> ApiException.notFound("Assignment not found"));
    if (row.revoked()) {
      return new Revoked(false);
    }
    jdbc.sql("UPDATE \"RoleAssignment\" SET \"revokedAt\" = :now WHERE id = :id")
        .param("now", SqlTime.utc(clock.now()))
        .param("id", assignmentId)
        .update();
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("role", "DEPT_ADMIN");
    before.put("orgUnitId", row.orgUnitId());
    before.put("assignmentId", assignmentId);
    audit.log(
        AuditService.Entry.of(actorId, "role.revoked", "Employee", row.employeeId())
            .withBefore(before));
    return new Revoked(true);
  }
}
