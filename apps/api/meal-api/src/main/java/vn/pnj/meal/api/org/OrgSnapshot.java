package vn.pnj.meal.api.org;

import java.text.Collator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import vn.pnj.meal.rules.access.ListScope;
import vn.pnj.meal.rules.org.OrgIndex;
import vn.pnj.meal.rules.org.OrgNode;

/** The whole org tree of a factory, indexed for the mapping rules (BR-60, BR-62, BR-63). */
public final class OrgSnapshot {
  private static final Collator VIETNAMESE = Collator.getInstance(Locale.forLanguageTag("vi"));

  /** One org node as the screens and rules need it. */
  public record Node(
      String id,
      String dmpCode,
      String name,
      int level,
      String dmpLevelName,
      String parentId,
      String path,
      boolean isMealUnit,
      String floorId,
      String canteenId,
      String costCenterId,
      boolean active) {

    OrgNode toRuleNode() {
      return new OrgNode(id, parentId, isMealUnit, floorId, canteenId, costCenterId);
    }
  }

  private final List<Node> nodes;
  private final Map<String, Node> byId;
  private final OrgIndex index;

  public OrgSnapshot(List<Node> nodes) {
    this.nodes = List.copyOf(nodes);
    this.byId = nodes.stream().collect(Collectors.toMap(Node::id, Function.identity()));
    this.index = OrgIndex.of(nodes.stream().map(Node::toRuleNode).toList());
  }

  public List<Node> nodes() {
    return nodes;
  }

  public Node get(String id) {
    return byId.get(id);
  }

  public OrgIndex index() {
    return index;
  }

  /** BR-61: screens show the full path below the company, e.g. "Khối Sản xuất / Xưởng Chế tác". */
  public String pathName(String nodeId) {
    Node node = byId.get(nodeId);
    if (node == null) {
      return "";
    }
    return Arrays.stream(node.path().split("/"))
        .filter(s -> !s.isEmpty())
        .map(byId::get)
        .filter(Objects::nonNull)
        .filter(n -> n.level() > 1)
        .map(Node::name)
        .collect(Collectors.joining(" / "));
  }

  /** Khối / Trung tâm / Phòng ban filter option (shape of {@code OrgFilterOption}). */
  public record FilterOption(
      String id, String name, @Nullable String parentId, int level, String levelName) {}

  /**
   * Org nodes a caller may filter by: active nodes inside the caller's list scope, in tree order. A
   * dept admin sees only their own subtree.
   */
  public List<FilterOption> filterOptions(ListScope scope) {
    return nodes.stream()
        .filter(Node::active)
        .filter(n -> inScope(scope, n.path()))
        .sorted(
            Comparator.comparingInt(Node::level)
                .thenComparing(Node::name, (a, b) -> VIETNAMESE.compare(a, b)))
        .map(n -> new FilterOption(n.id(), n.name(), n.parentId(), n.level(), n.dmpLevelName()))
        .toList();
  }

  private static boolean inScope(ListScope scope, String path) {
    return switch (scope) {
      case ListScope.All a -> true;
      case ListScope.Factories f -> true;
      case ListScope.Paths p -> p.paths().stream().anyMatch(path::startsWith);
      default -> false;
    };
  }
}
