package vn.pnj.meal.api.dmp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The only way the system talks to DMP (plan section 5, 7.1 rule 8). Read-only: it never writes
 * back and never calls POST /api/hr/attendance/generate-data.
 */
public interface DmpClient {

  /**
   * @param levelName level name as DMP reports it; the 1..6 level is computed by the org sync
   *     (BR-61, A14)
   */
  record OrgNode(
      String code, String name, String parentCode, String levelName, String costCenterCode) {}

  /**
   * @param employeeCode DMP employeeId, normalized to employeeCode
   * @param endDate last working day if DMP reports one (BR-36)
   */
  record Employee(
      String employeeCode,
      String name,
      String email,
      String orgUnitCode,
      String costCenterCode,
      LocalDate startDate,
      LocalDate endDate) {}

  /**
   * One attendance row of a day.
   *
   * @param checkInAt DMP local time read as Vietnam time (assumption A7), as a UTC instant; null
   *     when the row carries no check-in (a manager's confirmation alone)
   * @param managerConfirmed BR-30 / BR-32 (v1.3): a production manager ticked "present" in DMP; how
   *     DMP exposes it is open question Q27
   */
  record AttendanceRecord(String employeeCode, Instant checkInAt, boolean managerConfirmed) {
    public AttendanceRecord(String employeeCode, Instant checkInAt) {
      this(employeeCode, checkInAt, false);
    }
  }

  enum LeaveDayType {
    FULL,
    AM,
    PM
  }

  record LeaveRecord(
      String employeeCode, LocalDate date, LeaveDayType dayType, String leaveTypeName) {}

  List<OrgNode> getOrgTree();

  List<Employee> getEmployees();

  /** Whole-day attendance (DMP has no time filter). */
  List<AttendanceRecord> getAttendance(LocalDate date);

  /** Leave flags for the inclusive date range. */
  List<LeaveRecord> getLeave(LocalDate fromDate, LocalDate toDate);
}
