package vn.pnj.meal.api.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import vn.pnj.meal.api.db.Enums.AssignableRole;

/** A stored role. Revoked rows are kept for the record (never deleted); only null ones count. */
@Entity
@Table(name = "RoleAssignment")
public class RoleAssignment {
  @Id private String id;
  private String employeeId;

  @Enumerated(EnumType.STRING)
  @JdbcType(PostgreSQLEnumJdbcType.class)
  private AssignableRole role;

  private String factoryId;

  /** Only for DEPT_ADMIN: the org node (and its children) they manage. */
  private String orgUnitId;

  @Column(insertable = false, updatable = false)
  private Instant createdAt;

  private Instant revokedAt;

  protected RoleAssignment() {}

  public RoleAssignment(
      String id, String employeeId, AssignableRole role, String factoryId, String orgUnitId) {
    this.id = id;
    this.employeeId = employeeId;
    this.role = role;
    this.factoryId = factoryId;
    this.orgUnitId = orgUnitId;
  }

  public String getId() {
    return id;
  }

  public String getEmployeeId() {
    return employeeId;
  }

  public AssignableRole getRole() {
    return role;
  }

  public String getFactoryId() {
    return factoryId;
  }

  public String getOrgUnitId() {
    return orgUnitId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public void revoke(Instant at) {
    this.revokedAt = at;
  }
}
