package vn.pnj.meal.api.settings;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

/**
 * Per-factory settings and their defaults (plan section 6.4). Every deadline is read from here and
 * never hardcoded (plan 7.1 rule 3). Values are JSON, the same shapes as {@code
 * SETTING_DEFINITIONS} in packages/shared. {@code registration.close} stores the LAST accepted
 * minute (Thursday 23:59); the rules turn it into the exclusive closeAt (Friday 00:00, BR-03).
 */
public enum SettingKey {
  REGISTRATION_CLOSE(
      "registration.close", "{\"weekday\":4,\"time\":\"23:59\"}", Shapes::weekdayTime),
  REGISTRATION_LATE_PUBLISH_HOURS("registration.latePublishHours", "24", Shapes::positiveInt),
  MENU_PUBLISH_WARN_AT(
      "menu.publishWarnAt", "{\"weekday\":1,\"time\":\"12:00\"}", Shapes::weekdayTime),
  SERVING_DAYS("servingDays", "[1,2,3,4,5]", Shapes::weekdays),
  DAY_LOCK_TIME("day.lockTime", "\"08:00\"", Shapes::timeOfDay),
  ATTENDANCE_POLL_START("attendance.pollStart", "\"07:00\"", Shapes::timeOfDay),
  ATTENDANCE_POLL_INTERVAL_MIN("attendance.pollIntervalMin", "5", Shapes::positiveInt),
  ATTENDANCE_CUTOFF("attendance.cutoff", "\"08:10\"", Shapes::timeOfDay),
  ATTENDANCE_SAFETY_MIN_PRESENT_PCT("attendance.safetyMinPresentPct", "50", Shapes::percent),
  RECONCILIATION_DISH_DEVIATION_PCT("reconciliation.dishDeviationPct", "10", Shapes::percent),
  CLOSE_TIME("close.time", "\"08:30\"", Shapes::timeOfDay),
  CLOSE_REMINDER_AT("close.reminderAt", "\"08:45\"", Shapes::timeOfDay),
  CLOSE_ADJUSTMENT_DEADLINE("close.adjustmentDeadline", "\"10:30\"", Shapes::timeOfDay),
  DELIVERY_TOLERANCE_PCT("delivery.tolerancePct", "5", Shapes::percent),
  DELIVERY_CONFIRM_DEADLINE("delivery.confirmDeadline", "\"15:00\"", Shapes::timeOfDay),
  PIN_MAX_ATTEMPTS("pin.maxAttempts", "5", Shapes::positiveInt),
  PIN_LOCK_MINUTES("pin.lockMinutes", "15", Shapes::positiveInt);

  private final String code;
  private final String defaultJson;
  private final Predicate<JsonNode> valid;

  SettingKey(String code, String defaultJson, Predicate<JsonNode> valid) {
    this.code = code;
    this.defaultJson = defaultJson;
    this.valid = valid;
  }

  /** The key as stored in {@code Setting.key} and used by the web app. */
  public String code() {
    return code;
  }

  public String defaultJson() {
    return defaultJson;
  }

  public boolean accepts(JsonNode value) {
    return value != null && valid.test(value);
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
  static final class Shapes {
    private Shapes() {}

    static boolean timeOfDay(JsonNode node) {
      return node.isString() && node.asString().matches("([01]\\d|2[0-3]):[0-5]\\d");
    }

    static boolean positiveInt(JsonNode node) {
      return node.isIntegralNumber() && node.asLong() > 0;
    }

    static boolean percent(JsonNode node) {
      return node.isNumber() && node.asDouble() >= 0 && node.asDouble() <= 100;
    }

    static boolean weekday(JsonNode node) {
      return node.isIntegralNumber() && node.asInt() >= 1 && node.asInt() <= 7;
    }

    static boolean weekdayTime(JsonNode node) {
      return node.isObject() && weekday(node.path("weekday")) && timeOfDay(node.path("time"));
    }

    static boolean weekdays(JsonNode node) {
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
