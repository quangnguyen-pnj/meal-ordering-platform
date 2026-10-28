package vn.pnj.meal.api.org;

import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Reads the org tree and master-data names of a factory for the screens. */
@Service
public class OrgDirectory {
  private final JdbcClient jdbc;

  public OrgDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public OrgSnapshot load(String factoryId) {
    return new OrgSnapshot(
        jdbc.sql(
                """
                SELECT id, "dmpCode", name, level, "dmpLevelName", "parentId", path, "isMealUnit",
                       "floorId", "canteenId", "costCenterId", active
                FROM "OrgUnit" WHERE "factoryId" = :factoryId ORDER BY level, name
                """)
            .param("factoryId", factoryId)
            .query(OrgSnapshot.Node.class)
            .list());
  }

  /** Active cost centers by code, for resolving the DMP-supplied code (BR-62). */
  public Map<String, String> costCenterIdsByCode(String factoryId) {
    Map<String, String> byCode = new HashMap<>();
    jdbc.sql("SELECT id, code FROM \"CostCenter\" WHERE \"factoryId\" = :f AND active")
        .param("f", factoryId)
        .query((rs, i) -> byCode.put(rs.getString("code"), rs.getString("id")))
        .list();
    return byCode;
  }

  /** Display names of floors, canteens and cost centers by id (ids are unique across the three). */
  public Map<String, String> masterNames(String factoryId) {
    Map<String, String> names = new HashMap<>();
    jdbc.sql(
            """
            SELECT id, name FROM "Floor" WHERE "factoryId" = :f
            UNION ALL SELECT id, name FROM "Canteen" WHERE "factoryId" = :f
            UNION ALL SELECT id, code || ' · ' || name FROM "CostCenter" WHERE "factoryId" = :f
            """)
        .param("f", factoryId)
        .query((rs, i) -> names.put(rs.getString(1), rs.getString(2)))
        .list();
    return names;
  }
}
