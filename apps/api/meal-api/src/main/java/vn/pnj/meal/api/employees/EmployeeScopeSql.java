package vn.pnj.meal.api.employees;

import java.util.List;
import java.util.Map;
import vn.pnj.meal.rules.access.ListScope;

/**
 * Turns a list scope into SQL over {@code "Employee" e JOIN "OrgUnit" o} (plan section 3: every
 * list is filtered by scope). Paths use {@code starts_with}, not LIKE, because ids contain "_".
 */
public final class EmployeeScopeSql {
  private EmployeeScopeSql() {}

  public static String where(ListScope scope, Map<String, Object> params) {
    return switch (scope) {
      case ListScope.All a -> "TRUE";
      case ListScope.Factories f -> {
        params.put("scopeFactoryIds", f.factoryIds());
        yield "o.\"factoryId\" IN (:scopeFactoryIds)";
      }
      case ListScope.Paths p -> {
        List<String> clauses = new java.util.ArrayList<>();
        for (int i = 0; i < p.paths().size(); i++) {
          params.put("scopePath" + i, p.paths().get(i));
          clauses.add("starts_with(o.path, :scopePath" + i + ")");
        }
        yield "(" + String.join(" OR ", clauses) + ")";
      }
      case ListScope.Self s -> {
        params.put("scopeEmployeeId", s.employeeId());
        yield "e.id = :scopeEmployeeId";
      }
      case ListScope.None n -> "FALSE";
    };
  }
}
