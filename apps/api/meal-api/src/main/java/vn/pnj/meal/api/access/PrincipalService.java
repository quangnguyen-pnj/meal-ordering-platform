package vn.pnj.meal.api.access;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.pnj.meal.api.db.Enums.AuthMethod;
import vn.pnj.meal.rules.access.Role;

/** Builds the request's view of a signed-in employee. */
@Service
public class PrincipalService {
  private final JdbcClient jdbc;

  public PrincipalService(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private record EmployeeRow(
      String id,
      String employeeCode,
      String name,
      String email,
      String status,
      String factoryId,
      String mealUnitId,
      String mealUnitPath,
      boolean pinMustChange) {}

  private record RoleRow(
      String role, String factoryId, String orgUnitId, String path, Boolean active) {}

  /** Null when the employee may no longer sign in (unknown or left). */
  public RequestPrincipal load(String employeeId, String sessionId, AuthMethod method) {
    Optional<EmployeeRow> found =
        jdbc.sql(
                """
                SELECT e.id, e."employeeCode", e.name, e.email, e.status::text AS status,
                       o."factoryId", e."mealUnitId", mu.path AS "mealUnitPath", e."pinMustChange"
                FROM "Employee" e
                JOIN "OrgUnit" o ON o.id = e."orgUnitId"
                LEFT JOIN "OrgUnit" mu ON mu.id = e."mealUnitId"
                WHERE e.id = :id
                """)
            .param("id", employeeId)
            .query(EmployeeRow.class)
            .optional();
    if (found.isEmpty() || !"ACTIVE".equals(found.get().status())) {
      return null;
    }
    EmployeeRow e = found.get();
    List<RoleRow> assignments =
        jdbc.sql(
                """
                SELECT r.role::text AS role, r."factoryId", r."orgUnitId", o.path, o.active
                FROM "RoleAssignment" r
                LEFT JOIN "OrgUnit" o ON o.id = r."orgUnitId"
                WHERE r."employeeId" = :id AND r."revokedAt" IS NULL
                ORDER BY r."createdAt", r.id
                """)
            .param("id", employeeId)
            .query(RoleRow.class)
            .list();

    Set<Role> roles = new LinkedHashSet<>();
    roles.add(Role.EMPLOYEE);
    List<String> deptAdminOrgUnitIds = new ArrayList<>();
    List<String> deptAdminPaths = new ArrayList<>();
    List<String> hcFactoryIds = new ArrayList<>();
    for (RoleRow a : assignments) {
      Role role = Role.valueOf(a.role());
      roles.add(role);
      if (role == Role.HC_ADMIN) {
        hcFactoryIds.add(a.factoryId());
      }
      if (role == Role.DEPT_ADMIN && a.orgUnitId() != null && Boolean.TRUE.equals(a.active())) {
        deptAdminOrgUnitIds.add(a.orgUnitId());
        deptAdminPaths.add(a.path());
      }
    }
    return new RequestPrincipal(
        sessionId,
        e.id(),
        e.employeeCode(),
        e.name(),
        e.email(),
        e.factoryId(),
        e.mealUnitId(),
        e.mealUnitPath(),
        List.copyOf(roles),
        deptAdminOrgUnitIds,
        deptAdminPaths,
        hcFactoryIds,
        method == AuthMethod.PIN && e.pinMustChange());
  }

  /** The shape of {@code SessionUser} in packages/shared, for /auth/me and sign-in. */
  public record SessionUser(
      String id,
      String employeeCode,
      String name,
      @Nullable String email,
      List<Role> roles,
      String factoryId,
      @Nullable String mealUnitId,
      List<String> deptAdminOrgUnitIds,
      boolean pinMustChange) {}

  public SessionUser toSessionUser(RequestPrincipal p) {
    return new SessionUser(
        p.employeeId(),
        p.employeeCode(),
        p.name(),
        p.email(),
        p.roles(),
        p.factoryId(),
        p.mealUnitId(),
        p.deptAdminOrgUnitIds(),
        p.mustChangePin());
  }
}
