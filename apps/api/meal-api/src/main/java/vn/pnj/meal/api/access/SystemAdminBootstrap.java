package vn.pnj.meal.api.access;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.auth.OrgLookup;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.config.AppProperties;
import vn.pnj.meal.api.db.Employee;
import vn.pnj.meal.api.db.EmployeeRepository;
import vn.pnj.meal.api.db.Enums.AssignableRole;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.db.RoleAssignment;
import vn.pnj.meal.api.db.RoleAssignmentRepository;

/**
 * Plan section 3: the first system admin comes from an environment variable, and later system
 * admins are assigned by system admins. On startup (and after each employee sync, from J2) this
 * makes sure the configured employee holds the SYS_ADMIN role.
 */
@Service
public class SystemAdminBootstrap {
  private static final Logger log = LoggerFactory.getLogger(SystemAdminBootstrap.class);

  private final AppProperties properties;
  private final EmployeeRepository employees;
  private final RoleAssignmentRepository roles;
  private final OrgLookup org;
  private final AuditService audit;
  private final ClockService clock;

  public SystemAdminBootstrap(
      AppProperties properties,
      EmployeeRepository employees,
      RoleAssignmentRepository roles,
      OrgLookup org,
      AuditService audit,
      ClockService clock) {
    this.properties = properties;
    this.employees = employees;
    this.roles = roles;
    this.org = org;
    this.audit = audit;
    this.clock = clock;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    ensure();
  }

  /** Returns true when the role was newly granted. Safe to call repeatedly. */
  @Transactional
  public boolean ensure() {
    String code = properties.firstSystemAdminCode();
    if (code == null) {
      return false;
    }
    Employee employee = employees.findByEmployeeCode(code).orElse(null);
    if (employee == null) {
      log.warn(
          "FIRST_SYSTEM_ADMIN_CODE={} matches no employee yet; will retry after the next employee"
              + " sync",
          code);
      return false;
    }
    // Any SYS_ADMIN row counts, revoked ones too: a system admin who revoked the first admin must
    // not see the role come back at the next restart.
    if (roles.findByEmployeeId(employee.getId()).stream()
        .anyMatch(a -> a.getRole() == AssignableRole.SYS_ADMIN)) {
      return false;
    }
    roles.save(
        new RoleAssignment(
            Ids.newId(clock.now().toEpochMilli()),
            employee.getId(),
            AssignableRole.SYS_ADMIN,
            org.factoryOfOrgUnit(employee.getOrgUnitId()),
            null));
    audit.log(
        AuditService.Entry.of(null, "role.assigned", "Employee", employee.getId())
            .withAfter(Map.of("role", "SYS_ADMIN", "source", "FIRST_SYSTEM_ADMIN_CODE")));
    log.info("Granted SYS_ADMIN to {} from FIRST_SYSTEM_ADMIN_CODE", code);
    return true;
  }
}
