package vn.pnj.meal.api.system;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.pnj.meal.api.access.RequestPrincipal;
import vn.pnj.meal.api.access.RequirePermission;
import vn.pnj.meal.api.access.ScopeType;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.auth.OrgLookup;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.db.Employee;
import vn.pnj.meal.api.db.EmployeeRepository;
import vn.pnj.meal.api.db.Enums.AssignableRole;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.db.RoleAssignment;
import vn.pnj.meal.api.db.RoleAssignmentRepository;
import vn.pnj.meal.api.db.SqlTime;
import vn.pnj.meal.rules.access.Action;

/** /system: sync logs, and the HC and system admins (assigned by system admins only). */
@RestController
@RequestMapping("/system")
public class SystemController {
  private static final EnumSet<AssignableRole> PRIVILEGED =
      EnumSet.of(AssignableRole.HC_ADMIN, AssignableRole.SYS_ADMIN);

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final EmployeeRepository employees;
  private final RoleAssignmentRepository roles;
  private final OrgLookup org;
  private final AuditService audit;
  private final ClockService clock;

  public SystemController(
      JdbcClient jdbc,
      JsonMapper json,
      EmployeeRepository employees,
      RoleAssignmentRepository roles,
      OrgLookup org,
      AuditService audit,
      ClockService clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.employees = employees;
    this.roles = roles;
    this.org = org;
    this.audit = audit;
    this.clock = clock;
  }

  public record SyncRunView(
      String id,
      String type,
      Instant startedAt,
      Instant finishedAt,
      String status,
      Integer recordCount,
      String error,
      JsonNode details) {}

  public record Items<T>(List<T> items) {}

  /** Sync logs: HC admin (factory) and system admin only (plan section 3). */
  @RequirePermission(action = Action.SYNC_MANAGE, scope = ScopeType.FACTORY)
  @GetMapping("/sync-runs")
  Items<SyncRunView> syncRuns() {
    return new Items<>(
        jdbc.sql(
                """
                SELECT id, type::text, "startedAt", "finishedAt", status::text, "recordCount", error,
                       details::text AS details
                FROM "SyncRun" ORDER BY "startedAt" DESC LIMIT 50
                """)
            .query(
                (rs, i) ->
                    new SyncRunView(
                        rs.getString("id"),
                        rs.getString("type"),
                        SqlTime.instant(rs, "startedAt"),
                        SqlTime.instant(rs, "finishedAt"),
                        rs.getString("status"),
                        (Integer) rs.getObject("recordCount"),
                        rs.getString("error"),
                        rs.getString("details") == null
                            ? null
                            : json.readTree(rs.getString("details"))))
            .list());
  }

  public record RoleView(
      String id, String role, String employeeCode, String name, Instant createdAt) {}

  /** HC admins and system admins (plan section 3: assigned by system admins only). */
  @RequirePermission(action = Action.ROLES_ASSIGN_PRIVILEGED, scope = ScopeType.SYSTEM)
  @GetMapping("/roles")
  Items<RoleView> roles() {
    return new Items<>(
        roles.findByRoleInAndRevokedAtIsNullOrderByRoleAscCreatedAtAsc(PRIVILEGED).stream()
            .map(
                r -> {
                  Employee e = employees.findById(r.getEmployeeId()).orElseThrow();
                  return new RoleView(
                      r.getId(),
                      r.getRole().name(),
                      e.getEmployeeCode(),
                      e.getName(),
                      r.getCreatedAt());
                })
            .toList());
  }

  public record AssignBody(
      @NotNull @Size(min = 1, max = 32) String employeeCode,
      @NotNull @Pattern(regexp = "HC_ADMIN|SYS_ADMIN") String role) {
    public AssignBody {
      employeeCode = employeeCode == null ? null : employeeCode.trim();
    }
  }

  public record Assigned(String id, boolean created) {}

  @RequirePermission(action = Action.ROLES_ASSIGN_PRIVILEGED, scope = ScopeType.SYSTEM)
  @PostMapping("/roles")
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  Assigned assign(@Valid @RequestBody AssignBody body, RequestPrincipal principal) {
    Employee employee = employees.findByEmployeeCode(body.employeeCode()).orElse(null);
    if (employee == null || !employee.isActive()) {
      throw ApiException.invalid(
          "Unknown or inactive employee",
          List.of(Map.of("path", "employeeCode", "message", "unknown")));
    }
    AssignableRole role = AssignableRole.valueOf(body.role());
    var existing =
        roles.findByEmployeeIdAndRevokedAtIsNull(employee.getId()).stream()
            .filter(r -> r.getRole() == role)
            .findFirst();
    if (existing.isPresent()) {
      return new Assigned(existing.get().getId(), false);
    }
    RoleAssignment row =
        roles.save(
            new RoleAssignment(
                Ids.newId(clock.now().toEpochMilli()),
                employee.getId(),
                role,
                org.factoryOfOrgUnit(employee.getOrgUnitId()),
                null));
    audit.log(
        AuditService.Entry.of(principal.employeeId(), "role.assigned", "Employee", employee.getId())
            .withAfter(Map.of("role", role.name(), "assignmentId", row.getId())));
    return new Assigned(row.getId(), true);
  }

  public record Revoked(boolean revoked) {}

  @RequirePermission(action = Action.ROLES_ASSIGN_PRIVILEGED, scope = ScopeType.SYSTEM)
  @PostMapping("/roles/{id}/revoke")
  @Transactional
  Revoked revoke(@PathVariable String id, RequestPrincipal principal) {
    RoleAssignment row =
        roles
            .findById(id)
            .filter(r -> PRIVILEGED.contains(r.getRole()))
            .orElseThrow(() -> ApiException.notFound("Not found"));
    if (row.getRevokedAt() != null) {
      return new Revoked(false);
    }
    if (row.getRole() == AssignableRole.SYS_ADMIN
        && roles.countByRoleAndRevokedAtIsNull(AssignableRole.SYS_ADMIN) <= 1) {
      throw ApiException.invalidState("The last system admin cannot be revoked", null);
    }
    row.revoke(clock.now());
    audit.log(
        AuditService.Entry.of(
                principal.employeeId(), "role.revoked", "Employee", row.getEmployeeId())
            .withBefore(Map.of("role", row.getRole().name(), "assignmentId", id)));
    return new Revoked(true);
  }
}
