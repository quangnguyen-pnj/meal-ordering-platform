package vn.pnj.meal.api.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import vn.pnj.meal.api.db.Enums.EmployeeStatus;

/** An employee from DMP. Only the PIN fields and the HC admin's pick-up floor change here. */
@Entity
@Table(name = "Employee")
public class Employee {
  @Id private String id;

  /** DMP employeeId, normalized to employeeCode (plan section 5). */
  private String employeeCode;

  private String name;
  private String email;
  private String orgUnitId;

  /** Nearest flagged ancestor-or-self (BR-60); recomputed on sync or flag change. */
  private String mealUnitId;

  /** Where the employee picks up their meal when it is not their meal unit's floor (BR-63). */
  private String floorOverrideId;

  /** No longer read: cost center follows the org tree (2026-10-23). Kept, never deleted. */
  private String costCenterOverrideId;

  private String dmpCostCenterCode;

  @Enumerated(EnumType.STRING)
  @JdbcType(PostgreSQLEnumJdbcType.class)
  private EmployeeStatus status = EmployeeStatus.ACTIVE;

  private LocalDate startDate;
  private LocalDate endDate;
  private String pinHash;
  private boolean pinMustChange = true;
  private int pinFailedCount;
  private Instant lockedUntil;

  @Column(insertable = false, updatable = false)
  private Instant createdAt;

  protected Employee() {}

  public Employee(String id, String employeeCode, String name, String email, String orgUnitId) {
    this.id = id;
    this.employeeCode = employeeCode;
    this.name = name;
    this.email = email;
    this.orgUnitId = orgUnitId;
  }

  public String getId() {
    return id;
  }

  public String getEmployeeCode() {
    return employeeCode;
  }

  public String getName() {
    return name;
  }

  public String getEmail() {
    return email;
  }

  public String getOrgUnitId() {
    return orgUnitId;
  }

  public String getMealUnitId() {
    return mealUnitId;
  }

  public void setMealUnitId(String mealUnitId) {
    this.mealUnitId = mealUnitId;
  }

  public String getFloorOverrideId() {
    return floorOverrideId;
  }

  public void setFloorOverrideId(String floorOverrideId) {
    this.floorOverrideId = floorOverrideId;
  }

  public String getDmpCostCenterCode() {
    return dmpCostCenterCode;
  }

  public void setDmpCostCenterCode(String dmpCostCenterCode) {
    this.dmpCostCenterCode = dmpCostCenterCode;
  }

  public EmployeeStatus getStatus() {
    return status;
  }

  public boolean isActive() {
    return status == EmployeeStatus.ACTIVE;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public void setStartDate(LocalDate startDate) {
    this.startDate = startDate;
  }

  public String getPinHash() {
    return pinHash;
  }

  public boolean isPinMustChange() {
    return pinMustChange;
  }

  public int getPinFailedCount() {
    return pinFailedCount;
  }

  public Instant getLockedUntil() {
    return lockedUntil;
  }

  /** A new PIN: a temporary one must be changed at the next PIN sign-in. Clears any lock. */
  public void setPin(String pinHash, boolean mustChange) {
    this.pinHash = pinHash;
    this.pinMustChange = mustChange;
    this.pinFailedCount = 0;
    this.lockedUntil = null;
  }

  public void setPinFailures(int failedCount, Instant lockedUntil) {
    this.pinFailedCount = failedCount;
    this.lockedUntil = lockedUntil;
  }
}
