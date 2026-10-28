package vn.pnj.meal.api.access;

import java.util.List;
import java.util.Set;
import vn.pnj.meal.rules.access.AccessPrincipal;
import vn.pnj.meal.rules.access.Role;

/**
 * The signed-in employee as the API sees them, loaded on every request (so a revoked role or a
 * departure takes effect at once).
 *
 * @param mustChangePin true only for PIN sessions of an employee whose PIN is temporary
 */
public record RequestPrincipal(
    String sessionId,
    String employeeId,
    String employeeCode,
    String name,
    String email,
    String factoryId,
    String mealUnitId,
    String mealUnitPath,
    List<Role> roles,
    List<String> deptAdminOrgUnitIds,
    List<String> deptAdminPaths,
    List<String> hcFactoryIds,
    boolean mustChangePin) {

  public AccessPrincipal access() {
    return new AccessPrincipal(
        employeeId, Set.copyOf(roles), mealUnitPath, deptAdminPaths, hcFactoryIds);
  }

  /** HC and system admins may learn that something does not exist; others get 403 instead. */
  public boolean seesWholePopulation() {
    return !hcFactoryIds.isEmpty() || roles.contains(Role.SYS_ADMIN);
  }
}
