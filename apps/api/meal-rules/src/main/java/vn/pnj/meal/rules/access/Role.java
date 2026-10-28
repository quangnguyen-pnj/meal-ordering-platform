package vn.pnj.meal.rules.access;

/**
 * Roles stack on one employee account (plan section 3). EMPLOYEE is implicit for every active one.
 */
public enum Role {
  EMPLOYEE,
  DEPT_ADMIN,
  HC_ADMIN,
  SYS_ADMIN
}
