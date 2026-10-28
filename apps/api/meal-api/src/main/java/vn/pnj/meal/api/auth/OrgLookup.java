package vn.pnj.meal.api.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Small org-tree lookups the sign-in code needs. */
@Component
public class OrgLookup {
  private final JdbcClient jdbc;

  public OrgLookup(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public String factoryOfOrgUnit(String orgUnitId) {
    return jdbc.sql("SELECT \"factoryId\" FROM \"OrgUnit\" WHERE id = :id")
        .param("id", orgUnitId)
        .query(String.class)
        .single();
  }
}
