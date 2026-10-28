package vn.pnj.meal.api.db;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Delivery location of a meal unit's box. BR-62: deactivated, never deleted. */
@Entity
@Table(name = "Floor")
public class Floor {
  @Id private String id;
  private String factoryId;
  private String code;
  private String name;
  private int sortOrder;
  private boolean active = true;

  protected Floor() {}

  public Floor(String id, String factoryId, String code, String name, int sortOrder) {
    this.id = id;
    this.factoryId = factoryId;
    this.code = code;
    this.name = name;
    this.sortOrder = sortOrder;
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

  public int getSortOrder() {
    return sortOrder;
  }

  public boolean isActive() {
    return active;
  }
}
