package vn.pnj.meal.api.auth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.common.ApiErrorCode;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.common.Times;
import vn.pnj.meal.api.db.Employee;
import vn.pnj.meal.api.db.EmployeeRepository;
import vn.pnj.meal.api.db.Enums.AuthMethod;
import vn.pnj.meal.api.settings.SettingKey;
import vn.pnj.meal.api.settings.SettingsService;
import vn.pnj.meal.rules.auth.PinRules;

/** Employee code + 6-digit PIN sign-in (plan 4.1 /login, /change-pin; section 6.4 pin.*). */
@Service
public class PinAuthService {
  private static final SecureRandom RANDOM = new SecureRandom();

  private final EmployeeRepository employees;
  private final PinHasher hasher;
  private final ClockService clock;
  private final SettingsService settings;
  private final SessionService sessions;
  private final AuditService audit;
  private final OrgLookup org;

  /** Verified against when the code is unknown, so timing does not reveal which codes exist. */
  private final String dummyHash;

  public PinAuthService(
      EmployeeRepository employees,
      PinHasher hasher,
      ClockService clock,
      SettingsService settings,
      SessionService sessions,
      AuditService audit,
      OrgLookup org) {
    this.employees = employees;
    this.hasher = hasher;
    this.clock = clock;
    this.settings = settings;
    this.sessions = sessions;
    this.audit = audit;
    this.org = org;
    this.dummyHash = hasher.hash("000000");
  }

  public record SignedIn(String employeeId, SessionService.Created session) {}

  /** Signs in. Wrong attempts are counted even though the call ends in an error. */
  @Transactional(noRollbackFor = ApiException.class)
  public SignedIn login(String employeeCode, String pin, String userAgent) {
    Employee employee = employees.findByEmployeeCode(employeeCode).orElse(null);
    if (employee == null || !employee.isActive() || employee.getPinHash() == null) {
      hasher.matches(pin, dummyHash);
      throw invalidCredentials();
    }
    verifyOrRegisterFailure(employee, pin);
    if (employee.getPinFailedCount() != 0 || employee.getLockedUntil() != null) {
      employee.setPinFailures(0, null);
    }
    return new SignedIn(
        employee.getId(), sessions.create(employee.getId(), AuthMethod.PIN, userAgent));
  }

  /** Changes the caller's PIN, clears the forced-change flag and signs out their other sessions. */
  @Transactional(noRollbackFor = ApiException.class)
  public void changePin(String employeeId, String sessionId, String currentPin, String newPin) {
    Employee employee = employees.findById(employeeId).orElseThrow();
    if (employee.getPinHash() == null) {
      throw invalidCredentials();
    }
    verifyOrRegisterFailure(employee, currentPin);
    if (currentPin.equals(newPin)) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          ApiErrorCode.PIN_UNCHANGED,
          "New PIN must differ from the current PIN");
    }
    employee.setPin(hasher.hash(newPin), false);
    sessions.revokeAllForEmployee(employeeId, sessionId);
    audit.log(AuditService.Entry.of(employeeId, "auth.pin.changed", "Employee", employeeId));
  }

  /**
   * Admin PIN reset: issues a temporary PIN, forces a change at next sign-in and ends the
   * employee's sessions. The temporary PIN is returned once and never stored or audited in clear.
   */
  @Transactional
  public String resetPin(String actorId, String employeeCode, String reason) {
    Employee employee =
        employees
            .findByEmployeeCode(employeeCode)
            .orElseThrow(() -> ApiException.notFound("Employee not found"));
    String temporaryPin = String.format("%06d", RANDOM.nextInt(1_000_000));
    boolean before = employee.isPinMustChange();
    employee.setPin(hasher.hash(temporaryPin), true);
    sessions.revokeAllForEmployee(employee.getId(), null);
    audit.log(
        AuditService.Entry.of(actorId, "employee.pin.reset", "Employee", employee.getId())
            .withBefore(Map.of("pinMustChange", before))
            .withAfter(Map.of("pinMustChange", true))
            .withReason(reason));
    return temporaryPin;
  }

  private void verifyOrRegisterFailure(Employee employee, String pin) {
    Instant now = clock.now();
    if (PinRules.isLocked(employee.getLockedUntil(), now)) {
      throw locked(employee.getLockedUntil());
    }
    if (hasher.matches(pin, employee.getPinHash())) {
      return;
    }
    String factoryId = org.factoryOfOrgUnit(employee.getOrgUnitId());
    PinRules.FailureState next =
        PinRules.registerFailure(
            employee.getPinFailedCount(),
            settings.getInt(factoryId, SettingKey.PIN_MAX_ATTEMPTS),
            settings.getInt(factoryId, SettingKey.PIN_LOCK_MINUTES),
            now);
    employee.setPinFailures(next.failedCount(), next.lockedUntil());
    employees.save(employee);
    if (next.lockedUntil() != null) {
      throw locked(next.lockedUntil());
    }
    throw invalidCredentials();
  }

  private static ApiException invalidCredentials() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED, ApiErrorCode.INVALID_CREDENTIALS, "Invalid employee code or PIN");
  }

  private static ApiException locked(Instant until) {
    return new ApiException(
        HttpStatus.LOCKED,
        ApiErrorCode.PIN_LOCKED,
        "Too many wrong attempts",
        null,
        Times.iso(until));
  }
}
