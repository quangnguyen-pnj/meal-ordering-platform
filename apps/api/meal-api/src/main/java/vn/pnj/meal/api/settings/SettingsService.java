package vn.pnj.meal.api.settings;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.db.SqlTime;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.deadline.WeekdayTime;

/**
 * Per-factory settings with the plan 6.4 defaults. A missing or malformed row falls back to the
 * default, so a bad edit can never stop sign-in or a deadline check.
 */
@Service
public class SettingsService {
  private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final AuditService audit;
  private final ClockService clock;

  public SettingsService(JdbcClient jdbc, JsonMapper json, AuditService audit, ClockService clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.audit = audit;
    this.clock = clock;
  }

  public JsonNode get(String factoryId, SettingKey key) {
    Optional<String> stored =
        jdbc.sql(
                "SELECT value::text FROM \"Setting\" WHERE \"factoryId\" = :factoryId AND key = :key")
            .param("factoryId", factoryId)
            .param("key", key.code())
            .query(String.class)
            .optional();
    if (stored.isPresent()) {
      JsonNode value = json.readTree(stored.get());
      if (key.accepts(value)) {
        return value;
      }
      log.warn(
          "Setting \"{}\" of factory {} is malformed; using the default", key.code(), factoryId);
    }
    return defaultValue(key);
  }

  public JsonNode defaultValue(SettingKey key) {
    return json.readTree(key.defaultJson());
  }

  public int getInt(String factoryId, SettingKey key) {
    return get(factoryId, key).asInt();
  }

  public LocalTime getTime(String factoryId, SettingKey key) {
    return VnTime.parseTimeOfDay(get(factoryId, key).asString());
  }

  public WeekdayTime getWeekdayTime(String factoryId, SettingKey key) {
    JsonNode value = get(factoryId, key);
    return new WeekdayTime(
        DayOfWeek.of(value.path("weekday").asInt()),
        VnTime.parseTimeOfDay(value.path("time").asString()));
  }

  public List<Integer> getWeekdays(String factoryId, SettingKey key) {
    List<Integer> days = new ArrayList<>();
    get(factoryId, key).forEach(day -> days.add(day.asInt()));
    return List.copyOf(days);
  }

  /**
   * @param value any JSON (a JsonNode), typed Object so the API description says "any value"
   */
  public record SettingView(
      String key, Object value, Object defaultValue, @Nullable Instant updatedAt) {}

  /** Every plan 6.4 key with its effective value and default, for /admin/settings. */
  public List<SettingView> list(String factoryId) {
    Map<String, Instant> updatedAt = new HashMap<>();
    jdbc.sql("SELECT key, \"updatedAt\" FROM \"Setting\" WHERE \"factoryId\" = :factoryId")
        .param("factoryId", factoryId)
        .query((rs, i) -> updatedAt.put(rs.getString("key"), SqlTime.instant(rs, "updatedAt")))
        .list();
    return Arrays.stream(SettingKey.values())
        .map(
            key ->
                new SettingView(
                    key.code(), get(factoryId, key), defaultValue(key), updatedAt.get(key.code())))
        .toList();
  }

  /** Validates the value against the key's shape, stores it and audits before / after. */
  @Transactional
  public SettingView set(String actorId, String factoryId, String code, JsonNode raw) {
    SettingKey key = SettingKey.fromCode(code);
    if (key == null) {
      throw ApiException.notFound("Unknown setting");
    }
    if (!key.accepts(raw)) {
      throw ApiException.invalid(
          "Invalid value for this setting", List.of(Map.of("path", "value", "message", code)));
    }
    JsonNode value = key.normalize(raw);
    JsonNode before = get(factoryId, key);
    jdbc.sql(
            """
            INSERT INTO "Setting" (id, "factoryId", key, value, "updatedAt")
            VALUES (:id, :factoryId, :key, CAST(:value AS jsonb), :now)
            ON CONFLICT ("factoryId", key)
            DO UPDATE SET value = EXCLUDED.value, "updatedAt" = EXCLUDED."updatedAt"
            """)
        .param("id", Ids.newId(clock.now().toEpochMilli()))
        .param("factoryId", factoryId)
        .param("key", code)
        .param("value", json.writeValueAsString(value))
        .param("now", SqlTime.utc(clock.now()))
        .update();
    audit.log(
        AuditService.Entry.of(actorId, "setting.updated", "Setting", factoryId + ":" + code)
            .withBefore(before)
            .withAfter(value));
    return list(factoryId).stream().filter(s -> s.key().equals(code)).findFirst().orElseThrow();
  }
}
