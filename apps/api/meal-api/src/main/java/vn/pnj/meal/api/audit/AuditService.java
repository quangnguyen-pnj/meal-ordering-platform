package vn.pnj.meal.api.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.db.SqlTime;

/**
 * BR-70: who, when, before, after, reason. Append-only: there is deliberately no update or delete
 * method. Never put secrets (PINs, tokens) into before/after. Runs in the caller's transaction.
 */
@Service
public class AuditService {
  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final ClockService clock;

  public AuditService(JdbcClient jdbc, JsonMapper json, ClockService clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
  }

  /**
   * @param actorId null for automatic system actions
   * @param before any value Jackson can write, or null
   * @param after any value Jackson can write, or null
   */
  public record Entry(
      String actorId,
      String action,
      String entityType,
      String entityId,
      Object before,
      Object after,
      String reason) {
    public static Entry of(String actorId, String action, String entityType, String entityId) {
      return new Entry(actorId, action, entityType, entityId, null, null, null);
    }

    public Entry withBefore(Object value) {
      return new Entry(actorId, action, entityType, entityId, value, after, reason);
    }

    public Entry withAfter(Object value) {
      return new Entry(actorId, action, entityType, entityId, before, value, reason);
    }

    public Entry withReason(String value) {
      return new Entry(actorId, action, entityType, entityId, before, after, value);
    }
  }

  public void log(Entry entry) {
    var now = clock.now();
    jdbc.sql(
            """
            INSERT INTO "AuditLog" (id, "actorId", action, "entityType", "entityId", before, after, reason, at)
            VALUES (:id, :actorId, :action, :entityType, :entityId,
                    CAST(:before AS jsonb), CAST(:after AS jsonb), :reason, :at)
            """)
        .param("id", Ids.newId(now.toEpochMilli()))
        .param("actorId", entry.actorId())
        .param("action", entry.action())
        .param("entityType", entry.entityType())
        .param("entityId", entry.entityId())
        .param("before", entry.before() == null ? null : json.writeValueAsString(entry.before()))
        .param("after", entry.after() == null ? null : json.writeValueAsString(entry.after()))
        .param("reason", entry.reason())
        .param("at", SqlTime.utc(now))
        .update();
  }
}
