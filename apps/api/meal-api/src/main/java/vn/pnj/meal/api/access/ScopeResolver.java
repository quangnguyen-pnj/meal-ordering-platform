package vn.pnj.meal.api.access;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.pnj.meal.rules.access.ResourceScope;

/** Turns a route's declared scope into a concrete {@link ResourceScope} for the access check. */
@Service
public class ScopeResolver {
  private final JdbcClient jdbc;

  public ScopeResolver(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private record OrgRef(String factoryId, String path) {}

  /** Empty when the referenced resource does not exist. Not called for LIST. */
  public Optional<ResourceScope> resolve(
      ScopeType type, String paramValue, RequestPrincipal principal) {
    return switch (type) {
      case SYSTEM -> Optional.of(new ResourceScope.SystemWide());
      case FACTORY -> Optional.of(new ResourceScope.Factory(principal.factoryId()));
      case SELF -> Optional.of(new ResourceScope.Self(principal.employeeId()));
      case EMPLOYEE_PARAM ->
          paramValue == null
              ? Optional.empty()
              : jdbc.sql(
                      """
                      SELECT o."factoryId", o.path FROM "Employee" e
                      JOIN "OrgUnit" o ON o.id = e."orgUnitId" WHERE e."employeeCode" = :code
                      """)
                  .param("code", paramValue)
                  .query(OrgRef.class)
                  .optional()
                  .map(o -> new ResourceScope.Org(o.factoryId(), o.path()));
      case ORG_UNIT_PARAM ->
          paramValue == null
              ? Optional.empty()
              : jdbc.sql("SELECT \"factoryId\", path FROM \"OrgUnit\" WHERE id = :id")
                  .param("id", paramValue)
                  .query(OrgRef.class)
                  .optional()
                  .map(o -> new ResourceScope.Org(o.factoryId(), o.path()));
      case LIST ->
          throw new IllegalArgumentException("LIST scopes are resolved by the interceptor");
    };
  }
}
