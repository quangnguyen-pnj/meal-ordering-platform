package vn.pnj.meal.api.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import vn.pnj.meal.rules.org.OrgNode;

/**
 * Node of the up-to-6-level org tree (BR-61). Floor, canteen and cost center are inherited from the
 * nearest ancestor that has one (BR-62). {@code path} is "/rootId/.../thisId/".
 */
@Entity
@Table(name = "OrgUnit")
public class OrgUnit {
  @Id private String id;
  private String factoryId;
  private String dmpCode;
  private String name;
  private int level;
  private String dmpLevelName;
  private String parentId;
  private String path;
  private boolean isMealUnit;
  private String floorId;
  private String canteenId;
  private String costCenterId;
  private String dmpCostCenterCode;
  private boolean active = true;

  @Column(insertable = false, updatable = false)
  private Instant createdAt;

  protected OrgUnit() {}

  public OrgUnit(
      String id,
      String factoryId,
      String dmpCode,
      String name,
      int level,
      String dmpLevelName,
      String parentId,
      String path) {
    this.id = id;
    this.factoryId = factoryId;
    this.dmpCode = dmpCode;
    this.name = name;
    this.level = level;
    this.dmpLevelName = dmpLevelName;
    this.parentId = parentId;
    this.path = path;
  }

  /** The node as the mapping rules see it. */
  public OrgNode toRuleNode() {
    return new OrgNode(id, parentId, isMealUnit, floorId, canteenId, costCenterId);
  }

  public String getId() {
    return id;
  }

  public String getFactoryId() {
    return factoryId;
  }

  public String getDmpCode() {
    return dmpCode;
  }

  public String getName() {
    return name;
  }

  public int getLevel() {
    return level;
  }

  public String getDmpLevelName() {
    return dmpLevelName;
  }

  public String getParentId() {
    return parentId;
  }

  public String getPath() {
    return path;
  }

  public boolean isMealUnit() {
    return isMealUnit;
  }

  public void setMealUnit(boolean mealUnit) {
    this.isMealUnit = mealUnit;
  }

  public String getFloorId() {
    return floorId;
  }

  public void setFloorId(String floorId) {
    this.floorId = floorId;
  }

  public String getCanteenId() {
    return canteenId;
  }

  public void setCanteenId(String canteenId) {
    this.canteenId = canteenId;
  }

  public String getCostCenterId() {
    return costCenterId;
  }

  public void setCostCenterId(String costCenterId) {
    this.costCenterId = costCenterId;
  }

  public String getDmpCostCenterCode() {
    return dmpCostCenterCode;
  }

  public boolean isActive() {
    return active;
  }
}
