package vn.pnj.meal.api.db;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Where meals are served; menus are built per canteen (BR-15). Never deleted (BR-62). */
@Entity
@Table(name = "Canteen")
public class Canteen {
  @Id private String id;
  private String factoryId;
  private String code;
  private String name;
  private String supplierId;
  private boolean active = true;

  protected Canteen() {}

  public Canteen(String id, String factoryId, String code, String name, String supplierId) {
    this.id = id;
    this.factoryId = factoryId;
    this.code = code;
    this.name = name;
    this.supplierId = supplierId;
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

  public boolean isActive() {
    return active;
  }
}
