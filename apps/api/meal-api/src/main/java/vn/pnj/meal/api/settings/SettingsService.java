package vn.pnj.meal.api.settings;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.deadline.WeekdayTime;

/**
 * Per-factory settings with the plan 6.4 defaults. A missing or malformed row falls back to the
 * default, so a bad edit can never stop sign-in or a deadline check. Editing them (/admin/settings)
 * comes with phase J2.
 */
@Service
public class SettingsService {
  private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public SettingsService(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
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
}
