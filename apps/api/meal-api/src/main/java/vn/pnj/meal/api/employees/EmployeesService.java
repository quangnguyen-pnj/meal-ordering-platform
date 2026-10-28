package vn.pnj.meal.api.employees;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.db.Employee;
import vn.pnj.meal.api.db.EmployeeRepository;
import vn.pnj.meal.api.db.Floor;
import vn.pnj.meal.api.db.FloorRepository;
import vn.pnj.meal.api.org.OrgDirectory;
import vn.pnj.meal.api.org.OrgSnapshot;
import vn.pnj.meal.rules.access.ListScope;
import vn.pnj.meal.rules.org.Mapping;
import vn.pnj.meal.rules.org.Mapping.EmployeeMealAssignment;
import vn.pnj.meal.rules.org.Mapping.MealStatus;

@Service
public class EmployeesService {
  private final JdbcClient jdbc;
  private final OrgDirectory org;
  private final EmployeeRepository employees;
  private final FloorRepository floors;
  private final AuditService audit;

  public EmployeesService(
      JdbcClient jdbc,
      OrgDirectory org,
      EmployeeRepository employees,
      FloorRepository floors,
      AuditService audit) {
    this.jdbc = jdbc;
    this.org = org;
    this.employees = employees;
    this.floors = floors;
    this.audit = audit;
  }

  /**
   * @param orgUnitId employees at or below this org node (BR-61: filter by org path)
   */
  public record Filter(
      String q,
      String orgUnitId,
      String floorId,
      String canteenId,
      String costCenterId,
      MealStatus mealStatus,
      String status,
      int offset,
      int limit) {}

  public record Page(int total, List<EmployeeMappingView> items) {}

  private record Row(
      String id,
      String employeeCode,
      String name,
      String status,
      String orgUnitId,
      String floorOverrideId,
      String dmpCostCenterCode) {}

  /**
   * /admin/employees: scoped finder with the resolved meal unit, floor, canteen and cost center
   * (BR-60, BR-63). Floor / canteen / cost center are inherited values, so they are filtered after
   * resolution; the factory has ~1,700 employees, which fits comfortably in memory.
   */
  @Transactional(readOnly = true)
  public Page list(ListScope scope, String factoryId, Filter f) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT e.id, e."employeeCode", e.name, e.status::text AS status, e."orgUnitId",
                   e."floorOverrideId", e."dmpCostCenterCode"
            FROM "Employee" e JOIN "OrgUnit" o ON o.id = e."orgUnitId"
            WHERE\s""");
    Map<String, Object> params = new HashMap<>();
    sql.append(EmployeeScopeSql.where(scope, params));
    if (f.q() != null && !f.q().isEmpty()) {
      sql.append(
          " AND (strpos(lower(e.\"employeeCode\"), lower(:q)) > 0"
              + " OR strpos(lower(e.name), lower(:q)) > 0)");
      params.put("q", f.q());
    }
    if (f.status() != null) {
      sql.append(" AND e.status = CAST(:status AS \"EmployeeStatus\")");
      params.put("status", f.status());
    }
    if (f.orgUnitId() != null) {
      sql.append(
          " AND starts_with(o.path, COALESCE((SELECT path FROM \"OrgUnit\" WHERE id = :orgUnitId),"
              + " '-none-'))");
      params.put("orgUnitId", f.orgUnitId());
    }
    sql.append(" ORDER BY e.\"employeeCode\"");
    List<Row> rows = jdbc.sql(sql.toString()).params(params).query(Row.class).list();

    OrgSnapshot snapshot = org.load(factoryId);
    Map<String, String> ccByCode = org.costCenterIdsByCode(factoryId);
    Map<String, String> names = org.masterNames(factoryId);
    Map<String, List<String>> roles = activeRoles();

    List<EmployeeMappingView> described = new ArrayList<>();
    for (Row e : rows) {
      EmployeeMappingView view = describe(snapshot, e, ccByCode, names, roles);
      if ((f.floorId() == null || f.floorId().equals(view.floorId()))
          && (f.canteenId() == null || f.canteenId().equals(view.canteenId()))
          && (f.costCenterId() == null || f.costCenterId().equals(view.costCenterId()))
          && (f.mealStatus() == null || f.mealStatus() == view.mealStatus())) {
        described.add(view);
      }
    }
    int from = Math.min(f.offset(), described.size());
    int to = Math.min(from + f.limit(), described.size());
    return new Page(described.size(), List.copyOf(described.subList(from, to)));
  }

  /** Every active employee of the factory, described, by employee code (for /admin/org lists). */
  @Transactional(readOnly = true)
  public List<EmployeeMappingView> describeActive(String factoryId) {
    List<Row> rows =
        jdbc.sql(
                """
                SELECT e.id, e."employeeCode", e.name, e.status::text AS status, e."orgUnitId",
                       e."floorOverrideId", e."dmpCostCenterCode"
                FROM "Employee" e JOIN "OrgUnit" o ON o.id = e."orgUnitId"
                WHERE e.status = 'ACTIVE' AND o."factoryId" = :factoryId
                ORDER BY e."employeeCode"
                """)
            .param("factoryId", factoryId)
            .query(Row.class)
            .list();
    OrgSnapshot snapshot = org.load(factoryId);
    Map<String, String> ccByCode = org.costCenterIdsByCode(factoryId);
    Map<String, String> names = org.masterNames(factoryId);
    Map<String, List<String>> roles = activeRoles();
    return rows.stream().map(e -> describe(snapshot, e, ccByCode, names, roles)).toList();
  }

  @Transactional(readOnly = true)
  public List<OrgSnapshot.FilterOption> orgUnits(ListScope scope, String factoryId) {
    return org.load(factoryId).filterOptions(scope);
  }

  public record Changed(boolean changed) {}

  /**
   * BR-63: the floor where one employee picks up their meal, when it is not their meal unit's floor
   * (product owner, 2026-10-23); null goes back to the meal unit's floor. HC admin only, with a
   * reason, audited (BR-70). Forecast lines already taken keep the floor they were written with
   * (BR-64); the change applies from the next snapshot.
   */
  @Transactional
  public Changed setFloorOverride(
      String actorId, String factoryId, String employeeCode, String floorId, String reason) {
    Employee employee =
        employees
            .findByEmployeeCode(employeeCode)
            .orElseThrow(() -> ApiException.notFound("Employee not found"));
    String code = null;
    if (floorId != null) {
      Floor floor = floors.findById(floorId).orElse(null);
      if (floor == null || !floor.getFactoryId().equals(factoryId) || !floor.isActive()) {
        throw ApiException.invalid(
            "Unknown or inactive floor", List.of(Map.of("path", "floorId", "message", "x")));
      }
      code = floor.getCode();
    }
    if (java.util.Objects.equals(employee.getFloorOverrideId(), floorId)) {
      return new Changed(false);
    }
    String beforeCode =
        employee.getFloorOverrideId() == null
            ? null
            : floors.findById(employee.getFloorOverrideId()).map(Floor::getCode).orElse(null);
    employee.setFloorOverrideId(floorId);
    Map<String, Object> before = new HashMap<>();
    before.put("floorCode", beforeCode);
    Map<String, Object> after = new HashMap<>();
    after.put("floorCode", code);
    audit.log(
        AuditService.Entry.of(actorId, "employee.floorOverride.set", "Employee", employee.getId())
            .withBefore(before)
            .withAfter(after)
            .withReason(reason));
    return new Changed(true);
  }

  private Map<String, List<String>> activeRoles() {
    Map<String, Set<String>> byEmployee = new HashMap<>();
    jdbc.sql(
            """
            SELECT "employeeId", role::text FROM "RoleAssignment"
            WHERE "revokedAt" IS NULL ORDER BY "createdAt", id
            """)
        .query(
            (rs, i) ->
                byEmployee
                    .computeIfAbsent(rs.getString(1), k -> new LinkedHashSet<>())
                    .add(rs.getString(2)))
        .list();
    Map<String, List<String>> roles = new HashMap<>();
    byEmployee.forEach(
        (id, set) -> {
          List<String> list = new ArrayList<>();
          list.add("EMPLOYEE");
          list.addAll(set);
          roles.put(id, list);
        });
    return roles;
  }

  private static EmployeeMappingView describe(
      OrgSnapshot snapshot,
      Row e,
      Map<String, String> ccByCode,
      Map<String, String> names,
      Map<String, List<String>> roles) {
    EmployeeMealAssignment a =
        Mapping.resolveEmployeeMealAssignment(
            e.orgUnitId(),
            e.floorOverrideId(),
            e.dmpCostCenterCode() == null ? null : ccByCode.get(e.dmpCostCenterCode()),
            snapshot.index());
    OrgSnapshot.Node mealUnit = a.mealUnitId() == null ? null : snapshot.get(a.mealUnitId());
    return new EmployeeMappingView(
        e.employeeCode(),
        e.name(),
        e.status(),
        e.orgUnitId(),
        snapshot.pathName(e.orgUnitId()),
        a.mealUnitId(),
        mealUnit == null ? null : mealUnit.name(),
        a.status(),
        a.missing(),
        a.floorId(),
        a.floorId() == null ? null : names.get(a.floorId()),
        a.floorSource(),
        e.floorOverrideId(),
        a.canteenId(),
        a.canteenId() == null ? null : names.get(a.canteenId()),
        a.costCenterId(),
        a.costCenterId() == null ? null : names.get(a.costCenterId()),
        a.costCenterSource(),
        roles.getOrDefault(e.id(), List.of("EMPLOYEE")));
  }
}
