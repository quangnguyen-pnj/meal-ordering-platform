package vn.pnj.meal.api.settings;

import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Per-factory settings and their defaults (plan section 6.4). Every deadline is read from here and
 * never hardcoded (plan 7.1 rule 3). Values are JSON, the same shapes as {@code
 * SETTING_DEFINITIONS} in packages/shared. {@code registration.close} stores the LAST accepted
 * minute (Thursday 23:59); the rules turn it into the exclusive closeAt (Friday 00:00, BR-03).
 */
public enum SettingKey {
  REGISTRATION_CLOSE(
      "registration.close", "{\"weekday\":4,\"time\":\"23:59\"}", Shape.WEEKDAY_TIME),
  REGISTRATION_LATE_PUBLISH_HOURS("registration.latePublishHours", "24", Shape.POSITIVE_INT),
  MENU_PUBLISH_WARN_AT(
      "menu.publishWarnAt", "{\"weekday\":1,\"time\":\"12:00\"}", Shape.WEEKDAY_TIME),
  SERVING_DAYS("servingDays", "[1,2,3,4,5]", Shape.WEEKDAYS),
  DAY_LOCK_TIME("day.lockTime", "\"08:00\"", Shape.TIME_OF_DAY),
  ATTENDANCE_POLL_START("attendance.pollStart", "\"07:00\"", Shape.TIME_OF_DAY),
  ATTENDANCE_POLL_INTERVAL_MIN("attendance.pollIntervalMin", "5", Shape.POSITIVE_INT),
  ATTENDANCE_CUTOFF("attendance.cutoff", "\"08:10\"", Shape.TIME_OF_DAY),
  ATTENDANCE_SAFETY_MIN_PRESENT_PCT("attendance.safetyMinPresentPct", "50", Shape.PERCENT),
  RECONCILIATION_DISH_DEVIATION_PCT("reconciliation.dishDeviationPct", "10", Shape.PERCENT),
  CLOSE_TIME("close.time", "\"08:30\"", Shape.TIME_OF_DAY),
  CLOSE_REMINDER_AT("close.reminderAt", "\"08:45\"", Shape.TIME_OF_DAY),
  CLOSE_ADJUSTMENT_DEADLINE("close.adjustmentDeadline", "\"10:30\"", Shape.TIME_OF_DAY),
  DELIVERY_TOLERANCE_PCT("delivery.tolerancePct", "5", Shape.PERCENT),
  DELIVERY_CONFIRM_DEADLINE("delivery.confirmDeadline", "\"15:00\"", Shape.TIME_OF_DAY),
  PIN_MAX_ATTEMPTS("pin.maxAttempts", "5", Shape.POSITIVE_INT),
  PIN_LOCK_MINUTES("pin.lockMinutes", "15", Shape.POSITIVE_INT);

  private final String code;
  private final String defaultJson;
  private final Shape shape;

  SettingKey(String code, String defaultJson, Shape shape) {
    this.code = code;
    this.defaultJson = defaultJson;
    this.shape = shape;
  }

  /** The key as stored in {@code Setting.key} and used by the web app. */
  public String code() {
    return code;
  }

  public String defaultJson() {
    return defaultJson;
  }

  public boolean accepts(JsonNode value) {
    return value != null && shape.accepts(value);
  }

  /**
   * The value as stored: like a Zod parse, unknown keys of an object are dropped and a whole number
   * written as {@code 24.0} becomes {@code 24}. Call only on an accepted value.
   */
  public JsonNode normalize(JsonNode value) {
    JsonNodeFactory nodes = JsonNodeFactory.instance;
    return switch (shape) {
      case POSITIVE_INT -> nodes.numberNode(value.asLong());
      case WEEKDAY_TIME -> {
        ObjectNode object = nodes.objectNode();
        object.put("weekday", value.path("weekday").asInt());
        object.put("time", value.path("time").asString());
        yield object;
      }
      case WEEKDAYS -> {
        var array = nodes.arrayNode();
        value.forEach(day -> array.add(day.asInt()));
        yield array;
      }
      case TIME_OF_DAY, PERCENT -> value;
    };
  }

  public static SettingKey fromCode(String code) {
    for (SettingKey key : values()) {
      if (key.code.equals(code)) {
        return key;
      }
    }
    return null;
  }

  /** Value checks, the Java twins of the Zod schemas in packages/shared/src/settings.ts. */
  enum Shape {
    TIME_OF_DAY,
    POSITIVE_INT,
    PERCENT,
    WEEKDAY_TIME,
    WEEKDAYS;

    boolean accepts(JsonNode node) {
      return switch (this) {
        case TIME_OF_DAY -> timeOfDay(node);
        case POSITIVE_INT -> integral(node) && node.asLong() > 0;
        case PERCENT -> node.isNumber() && node.asDouble() >= 0 && node.asDouble() <= 100;
        case WEEKDAY_TIME ->
            node.isObject() && weekday(node.path("weekday")) && timeOfDay(node.path("time"));
        case WEEKDAYS -> weekdays(node);
      };
    }

    private static boolean timeOfDay(JsonNode node) {
      return node.isString() && node.asString().matches("([01]\\d|2[0-3]):[0-5]\\d");
    }

    /** z.number().int(): any number without a fractional part, 24.0 included. */
    private static boolean integral(JsonNode node) {
      return node.isNumber() && node.canConvertToExactIntegral();
    }

    private static boolean weekday(JsonNode node) {
      return integral(node) && node.asInt() >= 1 && node.asInt() <= 7;
    }

    private static boolean weekdays(JsonNode node) {
      if (!node.isArray() || node.isEmpty()) {
        return false;
      }
      Set<Integer> seen = new HashSet<>();
      for (JsonNode day : node) {
        if (!weekday(day)) {
          return false;
        }
        seen.add(day.asInt());
      }
      return !seen.isEmpty();
    }
  }
}
