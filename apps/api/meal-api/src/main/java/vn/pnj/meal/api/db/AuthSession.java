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
import vn.pnj.meal.api.db.Enums.AuthMethod;

/** A signed-in browser. Only the SHA-256 of the cookie token is stored. */
@Entity
@Table(name = "AuthSession")
public class AuthSession {
  @Id private String id;
  private String employeeId;
  private String tokenHash;

  /** How the user signed in; the forced PIN change only applies to PIN sessions. */
  @Enumerated(EnumType.STRING)
  @JdbcType(PostgreSQLEnumJdbcType.class)
  private AuthMethod method;

  private Instant expiresAt;
  private String userAgent;

  @Column(insertable = false, updatable = false)
  private Instant createdAt;

  protected AuthSession() {}

  public AuthSession(
      String id,
      String employeeId,
      String tokenHash,
      AuthMethod method,
      Instant expiresAt,
      String userAgent) {
    this.id = id;
    this.employeeId = employeeId;
    this.tokenHash = tokenHash;
    this.method = method;
    this.expiresAt = expiresAt;
    this.userAgent = userAgent;
  }

  public String getId() {
    return id;
  }

  public String getEmployeeId() {
    return employeeId;
  }

  public AuthMethod getMethod() {
    return method;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }
}
