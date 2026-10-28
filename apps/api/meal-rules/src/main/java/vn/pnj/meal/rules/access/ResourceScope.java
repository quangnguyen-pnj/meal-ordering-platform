package vn.pnj.meal.rules.access;

/**
 * What a request acts on. Org-tree paths are materialized as {@code /<id>/<id>/.../} with a
 * trailing slash, so {@code path.startsWith(ancestor)} is a safe "is within" test.
 */
public sealed interface ResourceScope {
  record Self(String employeeId) implements ResourceScope {}

  record Org(String factoryId, String orgUnitPath) implements ResourceScope {}

  record Factory(String factoryId) implements ResourceScope {}

  record SystemWide() implements ResourceScope {}
}
