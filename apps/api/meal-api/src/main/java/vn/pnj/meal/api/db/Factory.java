package vn.pnj.meal.api.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "Factory")
public class Factory {
  @Id private String id;
  private String code;
  private String name;
  private String dmpCompanyCode;
  private String timezone;

  @Column(insertable = false, updatable = false)
  private java.time.Instant createdAt;

  protected Factory() {}

  public Factory(String id, String code, String name, String dmpCompanyCode, String timezone) {
    this.id = id;
    this.code = code;
    this.name = name;
    this.dmpCompanyCode = dmpCompanyCode;
    this.timezone = timezone;
  }

  public String getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public String getDmpCompanyCode() {
    return dmpCompanyCode;
  }

  public String getTimezone() {
    return timezone;
  }
}
