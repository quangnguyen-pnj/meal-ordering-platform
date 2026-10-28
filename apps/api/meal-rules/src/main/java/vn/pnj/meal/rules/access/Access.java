package vn.pnj.meal.rules.access;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** The (role, action, scope) check of plan section 3. The API calls it once, in its guard. */
public final class Access {
  private Access() {}

  /** Paths end with "/", so a prefix test never confuses /ou_2/ with /ou_20/. */
  public static boolean isWithinPath(String path, String ancestor) {
    return path.startsWith(ancestor);
  }

  /** True when any of the caller's roles grants {@code action} over {@code scope}. */
  public static boolean authorize(AccessPrincipal principal, Action action, ResourceScope scope) {
    for (Role role : principal.roles()) {
      ScopeLevel level = action.grants().get(role);
      if (level != null && levelAllows(level, principal, scope)) {
        return true;
      }
    }
    return false;
  }

  /** True when any role could grant {@code action} at all, before looking at a concrete scope. */
  public static boolean hasAnyGrant(AccessPrincipal principal, Action action) {
    return principal.roles().stream().anyMatch(action.grants()::containsKey);
  }

  public static ListScope resolveListScope(AccessPrincipal principal, Action action) {
    Set<ScopeLevel> levels = EnumSet.noneOf(ScopeLevel.class);
    for (Role role : principal.roles()) {
      ScopeLevel level = action.grants().get(role);
      if (level != null) {
        levels.add(level);
      }
    }
    if (levels.contains(ScopeLevel.SYSTEM)) {
      return new ListScope.All();
    }
    if (levels.contains(ScopeLevel.FACTORY) && !principal.hcFactoryIds().isEmpty()) {
      return new ListScope.Factories(principal.hcFactoryIds());
    }
    List<String> paths = new ArrayList<>();
    if (levels.contains(ScopeLevel.UNITS)) {
      paths.addAll(principal.deptAdminPaths());
    }
    if (levels.contains(ScopeLevel.OWN_UNIT) && principal.mealUnitPath() != null) {
      paths.add(principal.mealUnitPath());
    }
    if (!paths.isEmpty()) {
      return new ListScope.Paths(paths);
    }
    if (levels.contains(ScopeLevel.SELF)) {
      return new ListScope.Self(principal.employeeId());
    }
    return new ListScope.None();
  }

  /** Does a row at {@code orgUnitPath} of {@code factoryId} fall inside the list scope? */
  public static boolean listScopeIncludes(
      ListScope scope, String factoryId, String orgUnitPath, String employeeId) {
    return switch (scope) {
      case ListScope.None n -> false;
      case ListScope.All a -> true;
      case ListScope.Factories f -> f.factoryIds().contains(factoryId);
      case ListScope.Paths p ->
          p.paths().stream().anyMatch(path -> isWithinPath(orgUnitPath, path));
      case ListScope.Self s -> s.employeeId().equals(employeeId);
    };
  }

  private static boolean levelAllows(
      ScopeLevel level, AccessPrincipal principal, ResourceScope scope) {
    return switch (level) {
      case SYSTEM -> true;
      case FACTORY ->
          switch (scope) {
            case ResourceScope.Org org -> principal.hcFactoryIds().contains(org.factoryId());
            case ResourceScope.Factory factory ->
                principal.hcFactoryIds().contains(factory.factoryId());
            default -> false;
          };
      case UNITS ->
          scope instanceof ResourceScope.Org org
              && principal.deptAdminPaths().stream()
                  .anyMatch(assigned -> isWithinPath(org.orgUnitPath(), assigned));
      case OWN_UNIT ->
          scope instanceof ResourceScope.Org org
              && principal.mealUnitPath() != null
              && isWithinPath(org.orgUnitPath(), principal.mealUnitPath());
      case SELF ->
          scope instanceof ResourceScope.Self self
              && self.employeeId().equals(principal.employeeId());
    };
  }
}
