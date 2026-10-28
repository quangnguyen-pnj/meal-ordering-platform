package vn.pnj.meal.rules.org;

/** What the mapping rules need of an org-tree node (BR-60, BR-62). Null ids mean "not set here". */
public record OrgNode(
    String id,
    String parentId,
    boolean isMealUnit,
    String floorId,
    String canteenId,
    String costCenterId) {}
