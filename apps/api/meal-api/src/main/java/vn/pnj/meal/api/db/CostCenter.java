package vn.pnj.meal.api.db;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import vn.pnj.meal.api.db.Enums.CostCenterSource;

/** Accounting unit that bears an employee's meal cost. Never deleted (BR-62). */
@Entity
@Table(name = "CostCenter")
public class CostCenter {
  @Id private String id;
  private String factoryId;
  private String code;
  private String name;

  @Enumerated(EnumType.STRING)
  @JdbcType(PostgreSQLEnumJdbcType.class)
  private CostCenterSource source = CostCenterSource.MANUAL;

  private boolean active = true;

  protected CostCenter() {}

  public CostCenter(
      String id, String factoryId, String code, String name, CostCenterSource source) {
    this.id = id;
    this.factoryId = factoryId;
    this.code = code;
    this.name = name;
    this.source = source;
  }

  public String getId() {
    return id;
  }

  public String getFactoryId() {
    return factoryId;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public CostCenterSource getSource() {
    return source;
  }

  public boolean isActive() {
    return active;
  }
}
