package vn.pnj.meal.api.access;

/**
 * How a route's scope is derived. The interceptor resolves it and checks the (role, action, scope)
 * triple; handlers never re-implement the check.
 */
public enum ScopeType {
  SYSTEM,
  /** The caller's own factory (single factory in the MVP). */
  FACTORY,
  SELF,
  /** A path variable holds an employee code; the scope is that employee's org unit. */
  EMPLOYEE_PARAM,
  ORG_UNIT_PARAM,
  /** A list endpoint: the handler filters by the {@code ListScope} it receives. */
  LIST
}
