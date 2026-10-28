package vn.pnj.meal.rules.access;

import java.util.List;

/**
 * Filter a list query must apply for an action (plan section 3: every list query by a dept admin is
 * filtered by scope).
 */
public sealed interface ListScope {
  record None() implements ListScope {}

  record All() implements ListScope {}

  record Factories(List<String> factoryIds) implements ListScope {
    public Factories {
      factoryIds = List.copyOf(factoryIds);
    }
  }

  record Paths(List<String> paths) implements ListScope {
    public Paths {
      paths = List.copyOf(paths);
    }
  }

  record Self(String employeeId) implements ListScope {}
}
