package vn.pnj.meal.api.db;

/** Postgres enum types of the schema, by the same names. */
public final class Enums {
  private Enums() {}

  public enum EmployeeStatus {
    ACTIVE,
    LEFT
  }

  /** EMPLOYEE is implicit for every active employee and is not stored. */
  public enum AssignableRole {
    DEPT_ADMIN,
    HC_ADMIN,
    SYS_ADMIN
  }

  public enum AuthMethod {
    PIN,
    ENTRA
  }

  public enum CostCenterSource {
    DMP,
    MANUAL
  }

  public enum SyncType {
    ORG_EMPLOYEES,
    ATTENDANCE,
    LEAVE
  }

  public enum SyncStatus {
    RUNNING,
    SUCCESS,
    FAILED
  }
}
