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

  private OrgIndex(Map<String, OrgNode> byId) {
    this.byId = byId;
  }

  public static OrgIndex of(Iterable<OrgNode> nodes) {
    Map<String, OrgNode> map = new HashMap<>();
    for (OrgNode node : nodes) {
      map.put(node.id(), node);
    }
    return new OrgIndex(map);
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
