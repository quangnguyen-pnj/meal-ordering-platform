package vn.pnj.meal.api.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Reads a PATCH-style JSON body field by field. A record cannot tell an absent field ("leave as
 * is") from an explicit {@code null} ("clear it"); the partial Zod schemas of the legacy API could,
 * and the web relies on it. Problems are collected and thrown together as VALIDATION_FAILED with
 * the same {@code [{path, message}]} details as the record validation.
 */
public final class JsonBody {
  private final JsonNode node;
  private final List<Map<String, String>> issues = new ArrayList<>();

  private JsonBody(JsonNode node) {
    this.node = node;
  }

  public static JsonBody of(JsonNode node) {
    if (node == null || !node.isObject()) {
      throw ApiException.invalid(
          "Request validation failed", List.of(Map.of("path", "", "message", "Expected object")));
    }
    return new JsonBody(node);
  }

  /** Throws VALIDATION_FAILED for one field, like a failed Zod refinement. */
  public static void fail(String field, String message) {
    throw ApiException.invalid(
        "Request validation failed", List.of(Map.of("path", field, "message", message)));
  }

  /** True when the field is present, even with a null value. */
  public boolean has(String field) {
    return node.has(field);
  }

  /** Any field present at all. */
  public boolean isEmpty() {
    return node.isEmpty();
  }

  private void issue(String field, String message) {
    issues.add(Map.of("path", field, "message", message));
  }

  /** A trimmed string of {@code min..max} characters, or null when absent or invalid. */
  public String string(String field, int min, int max) {
    return string(field, min, max, null);
  }

  public String string(String field, int min, int max, Pattern pattern) {
    if (!has(field)) {
      return null;
    }
    JsonNode value = node.get(field);
    if (!value.isString()) {
      issue(field, "Expected string");
      return null;
    }
    String text = value.asString().strip();
    if (text.length() < min || text.length() > max) {
      issue(field, "Expected " + min + " to " + max + " characters");
      return null;
    }
    if (pattern != null && !pattern.matcher(text).matches()) {
      issue(field, "Invalid format");
      return null;
    }
    return text;
  }

  /** A non-empty id or an explicit null; call only when {@link #has} is true. */
  public String nullableId(String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isString() || value.asString().isEmpty()) {
      issue(field, "Expected id or null");
      return null;
    }
    return value.asString();
  }

  public Boolean bool(String field) {
    if (!has(field)) {
      return null;
    }
    JsonNode value = node.get(field);
    if (!value.isBoolean()) {
      issue(field, "Expected boolean");
      return null;
    }
    return value.asBoolean();
  }

  /** An integer in {@code min..max}; null when absent or invalid. */
  public Integer integer(String field, int min, int max) {
    if (!has(field)) {
      return null;
    }
    return checkedInt(field, node.get(field), min, max);
  }

  /**
   * An integer in {@code min..max} or an explicit null; call only when {@link #has} is true. The
   * result tells null apart from invalid through {@link #valid()}.
   */
  public Integer nullableInteger(String field, int min, int max) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : checkedInt(field, value, min, max);
  }

  private Integer checkedInt(String field, JsonNode value, int min, int max) {
    if (!value.isNumber() || !value.canConvertToExactIntegral()) {
      issue(field, "Expected integer");
      return null;
    }
    long n = value.asLong();
    if (n < min || n > max) {
      issue(field, "Expected " + min + " to " + max);
      return null;
    }
    return (int) n;
  }

  public void refine(boolean ok, String field, String message) {
    if (!ok) {
      issue(field, message);
    }
  }

  public boolean valid() {
    return issues.isEmpty();
  }

  /** Throws VALIDATION_FAILED when any field was rejected. */
  public void requireValid() {
    if (!issues.isEmpty()) {
      throw ApiException.invalid("Request validation failed", List.copyOf(issues));
    }
  }
}
