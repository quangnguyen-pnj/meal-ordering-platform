package vn.pnj.meal.rules.org;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BR-61: DMP org nodes become a tree of up to 6 levels. Each node keeps the DMP level name; the
 * level number is the node's depth from its root.
 *
 * <p>TODO(BR-61): assumption A14 (DMP hierarchy levels map onto BRQ's 6 levels by depth) still
 * needs the DMP team's confirmation (docs/open-questions.md). Only {@link #mapDmpLevel} changes if
 * it doesn't.
 */
public final class DmpTree {
  public static final int MAX_ORG_LEVEL = 6;

  private DmpTree() {}

  /** BR-61: depth 1..6 is the level; anything else has none. */
  public static Integer mapDmpLevel(int depth) {
    return depth >= 1 && depth <= MAX_ORG_LEVEL ? depth : null;
  }

  public record Input(
      String code, String name, String parentCode, String levelName, String costCenterCode) {}

  public record Node(
      String code,
      String name,
      String parentCode,
      String levelName,
      String costCenterCode,
      int level) {}

  public enum ErrorCode {
    DUPLICATE_CODE,
    UNKNOWN_PARENT,
    CYCLE,
    TOO_DEEP
  }

  /**
   * @param parentCode {@code UNKNOWN_PARENT} only
   * @param depth {@code TOO_DEEP} only
   */
  public record Error(ErrorCode code, String nodeCode, String parentCode, Integer depth) {}

  /** Either the nodes, parents first, or every error. */
  public record Result(List<Node> nodes, List<Error> errors) {
    public boolean ok() {
      return errors.isEmpty();
    }
  }

  /**
   * Validates a DMP org tree and orders it parents first. Any error rejects the whole tree, so a
   * broken sync never half-writes the org structure (plan section 5: data is written only when the
   * whole run succeeds).
   */
  public static Result build(List<Input> input) {
    List<Error> errors = new ArrayList<>();
    Map<String, Input> byCode = new LinkedHashMap<>();
    for (Input node : input) {
      if (byCode.putIfAbsent(node.code(), node) != null) {
        errors.add(new Error(ErrorCode.DUPLICATE_CODE, node.code(), null, null));
      }
    }
    for (Input node : byCode.values()) {
      if (node.parentCode() != null && !byCode.containsKey(node.parentCode())) {
        errors.add(new Error(ErrorCode.UNKNOWN_PARENT, node.code(), node.parentCode(), null));
      }
    }
    if (!errors.isEmpty()) {
      return new Result(List.of(), List.copyOf(errors));
    }

    Map<String, Integer> depthOf = new HashMap<>();
    List<Node> nodes = new ArrayList<>();
    for (Input node : byCode.values()) {
      Integer depth = resolveDepth(node.code(), byCode, depthOf);
      if (depth == null) {
        errors.add(new Error(ErrorCode.CYCLE, node.code(), null, null));
        continue;
      }
      Integer level = mapDmpLevel(depth);
      if (level == null) {
        errors.add(new Error(ErrorCode.TOO_DEEP, node.code(), null, depth));
        continue;
      }
      nodes.add(
          new Node(
              node.code(),
              node.name(),
              node.parentCode(),
              node.levelName(),
              node.costCenterCode(),
              level));
    }
    if (!errors.isEmpty()) {
      return new Result(List.of(), List.copyOf(errors));
    }
    nodes.sort(Comparator.comparingInt(Node::level).thenComparing(Node::code));
    return new Result(List.copyOf(nodes), List.of());
  }

  /** Walks up to a node of known depth (or a root), then fills in the chain; null on a cycle. */
  private static Integer resolveDepth(
      String code, Map<String, Input> byCode, Map<String, Integer> depthOf) {
    Integer known = depthOf.get(code);
    if (known != null) {
      return known;
    }
    List<String> chain = new ArrayList<>();
    Set<String> onChain = new LinkedHashSet<>();
    String current = code;
    int base = 0;
    while (current != null) {
      Integer cached = depthOf.get(current);
      if (cached != null) {
        base = cached;
        break;
      }
      if (!onChain.add(current)) {
        return null;
      }
      chain.add(current);
      current = byCode.get(current).parentCode();
    }
    for (int i = chain.size() - 1; i >= 0; i--) {
      base += 1;
      depthOf.put(chain.get(i), base);
    }
    return depthOf.get(code);
  }
}
