package vn.pnj.meal.rules.access;

import java.util.List;
import java.util.Set;

/**
 * The signed-in user as the access rules see them.
 *
 * @param roles always includes EMPLOYEE for an active employee
 * @param mealUnitPath path of the caller's own meal unit, if any (OWN_UNIT grants)
 * @param deptAdminPaths paths of the org nodes a DEPT_ADMIN manages (UNITS grants)
 * @param hcFactoryIds factories the caller is HC admin of (FACTORY grants)
 */
public record AccessPrincipal(
    String employeeId,
    Set<Role> roles,
    String mealUnitPath,
    List<String> deptAdminPaths,
    List<String> hcFactoryIds) {

  public AccessPrincipal {
    roles = Set.copyOf(roles);
    deptAdminPaths = List.copyOf(deptAdminPaths);
    hcFactoryIds = List.copyOf(hcFactoryIds);
  }
}
