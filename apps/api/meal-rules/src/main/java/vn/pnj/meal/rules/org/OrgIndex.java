package vn.pnj.meal.rules.org;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An org tree indexed by node id. */
public final class OrgIndex {
  private final Map<String, OrgNode> byId;
  private final String fallbackMealUnitId;

  private OrgIndex(Map<String, OrgNode> byId, String fallbackMealUnitId) {
    this.byId = byId;
    this.fallbackMealUnitId = fallbackMealUnitId;
  }

  public static OrgIndex of(Iterable<OrgNode> nodes) {
    return of(nodes, null);
  }

  /**
   * @param fallbackMealUnitId the factory's fallback meal unit ("đầu mối nhận cơm tập trung", CR
   *     2026-11-04), or null when the HC admin has not picked one
   */
  public static OrgIndex of(Iterable<OrgNode> nodes, String fallbackMealUnitId) {
    Map<String, OrgNode> map = new HashMap<>();
    for (OrgNode node : nodes) {
      map.put(node.id(), node);
    }
    return new OrgIndex(map, fallbackMealUnitId);
  }

  /**
   * The fallback meal unit, or null when none is set or it is no longer a meal unit of this tree.
   */
  public String fallbackMealUnitId() {
    OrgNode node = fallbackMealUnitId == null ? null : byId.get(fallbackMealUnitId);
    return node != null && node.isMealUnit() ? node.id() : null;
  }

  public OrgNode get(String id) {
    return byId.get(id);
  }

  /** The node itself, then its parent, grandparent... up to the root. */
  public List<OrgNode> nodeAndAncestors(String nodeId) {
    List<OrgNode> chain = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    String currentId = nodeId;
    while (currentId != null) {
      if (!seen.add(currentId)) {
        throw new IllegalStateException("Org tree cycle detected at node \"" + currentId + "\"");
      }
      OrgNode node = byId.get(currentId);
      if (node == null) {
        throw new IllegalArgumentException("Unknown org node \"" + currentId + "\"");
      }
      chain.add(node);
      currentId = node.parentId();
    }
    return chain;
  }
}
