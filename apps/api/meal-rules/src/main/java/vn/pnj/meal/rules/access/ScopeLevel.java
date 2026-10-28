package vn.pnj.meal.rules.access;

/**
 * How far a role's grant reaches. SELF: the caller's own data. OWN_UNIT: the caller's meal unit.
 * UNITS: assigned org nodes and their children. FACTORY: the whole factory. SYSTEM: everything.
 */
public enum ScopeLevel {
  SELF,
  OWN_UNIT,
  UNITS,
  FACTORY,
  SYSTEM
}
