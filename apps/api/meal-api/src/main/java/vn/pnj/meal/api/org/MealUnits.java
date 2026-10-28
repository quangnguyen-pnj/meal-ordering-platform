package vn.pnj.meal.api.org;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.pnj.meal.rules.org.Mapping;

/** BR-60: keeps {@code Employee.mealUnitId} equal to the nearest meal unit of the org tree. */
@Service
public class MealUnits {
  private final JdbcClient jdbc;
  private final OrgDirectory org;

  public MealUnits(JdbcClient jdbc, OrgDirectory org) {
    this.jdbc = jdbc;
    this.org = org;
  }

  private record Row(String id, String orgUnitId, String mealUnitId) {}

  /**
   * Recomputes the meal unit of every employee of the factory whose nearest flagged node changed
   * (after a flag change, an org sync or a tree move). Runs in the caller's transaction. Returns
   * how many employees changed.
   */
  public int recompute(String factoryId) {
    OrgSnapshot snapshot = org.load(factoryId);
    List<Row> employees =
        jdbc.sql(
                """
                SELECT e.id, e."orgUnitId", e."mealUnitId" FROM "Employee" e
                JOIN "OrgUnit" o ON o.id = e."orgUnitId" WHERE o."factoryId" = :factoryId
                """)
            .param("factoryId", factoryId)
            .query(Row.class)
            .list();
    Map<String, List<String>> moves = new HashMap<>();
    List<String> toNone = new ArrayList<>();
    for (Row e : employees) {
      String target = Mapping.resolveMealUnitId(e.orgUnitId(), snapshot.index());
      if (Objects.equals(target, e.mealUnitId())) {
        continue;
      }
      if (target == null) {
        toNone.add(e.id());
      } else {
        moves.computeIfAbsent(target, k -> new ArrayList<>()).add(e.id());
      }
    }
    int changed = 0;
    for (var move : moves.entrySet()) {
      changed +=
          jdbc.sql("UPDATE \"Employee\" SET \"mealUnitId\" = :target WHERE id IN (:ids)")
              .param("target", move.getKey())
              .param("ids", move.getValue())
              .update();
    }
    if (!toNone.isEmpty()) {
      changed +=
          jdbc.sql("UPDATE \"Employee\" SET \"mealUnitId\" = NULL WHERE id IN (:ids)")
              .param("ids", toNone)
              .update();
    }
    return changed;
  }
}
