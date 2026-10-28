package vn.pnj.meal.api.dmp;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Response parsing for the DMP endpoints used from P1 (plan section 5). Swagger has no field
 * descriptions, so every field name below is a best reading of the dmp-backend survey.
 *
 * <p>TODO(BR-61): confirm the field names against DMP /v3/api-docs before syncing real data (plan
 * section 5 and section 8 dependencies).
 */
final class DmpParsers {
  private static final Pattern ISO_DATE = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})");
  private static final Pattern DMY_DATE = Pattern.compile("^(\\d{2})-(\\d{2})-(\\d{4})$");

  private DmpParsers() {}

  /** A non-blank string (trimmed) or a number as text; null otherwise. */
  private static String str(JsonNode value) {
    if (value == null) {
      return null;
    }
    if (value.isString()) {
      String text = value.asString().strip();
      return text.isEmpty() ? null : text;
    }
    return value.isNumber() ? value.asString() : null;
  }

  private static String pick(JsonNode node, String... keys) {
    for (String key : keys) {
      String value = str(node.get(key));
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  /** GET /api/hierarchy returns the full tree, unpaged: a root node or a list of roots. */
  static List<DmpClient.OrgNode> parseHierarchy(JsonNode body) {
    if (body == null || !(body.isArray() || body.isObject())) {
      throw new DmpException(DmpException.Kind.API_ERROR, "DMP /api/hierarchy returned no tree");
    }
    List<DmpClient.OrgNode> out = new ArrayList<>();
    if (body.isArray()) {
      body.forEach(root -> walk(root, null, 1, out));
    } else {
      walk(body, null, 1, out);
    }
    return out;
  }

  private static void walk(
      JsonNode node, String parentCode, int depth, List<DmpClient.OrgNode> out) {
    if (node == null || !node.isObject()) {
      return;
    }
    String code = pick(node, "code", "unitCode", "id");
    if (code == null) {
      throw new DmpException(DmpException.Kind.API_ERROR, "DMP hierarchy node without a code");
    }
    String name = pick(node, "name", "unitName");
    String levelName = pick(node, "levelName", "level", "type");
    out.add(
        new DmpClient.OrgNode(
            code,
            name != null ? name : code,
            parentCode,
            levelName != null ? levelName : "Cấp " + depth,
            pick(node, "costCenterCode", "costCenter")));
    JsonNode children = firstPresent(node, "children", "childs", "subUnits");
    if (children != null && children.isArray()) {
      children.forEach(child -> walk(child, code, depth + 1, out));
    }
  }

  /** JavaScript's {@code a ?? b ?? c}: the first field that is present and not null. */
  private static JsonNode firstPresent(JsonNode node, String... keys) {
    for (String key : keys) {
      JsonNode value = node.get(key);
      if (value != null && !value.isNull()) {
        return value;
      }
    }
    return null;
  }

  /** {@code YYYY-MM-DD...} or {@code DD-MM-YYYY}; null when absent or in another format. */
  static LocalDate toDate(String value) {
    if (value == null) {
      return null;
    }
    Matcher iso = ISO_DATE.matcher(value);
    Matcher dmy = DMY_DATE.matcher(value);
    try {
      if (iso.find()) {
        return LocalDate.of(
            Integer.parseInt(iso.group(1)),
            Integer.parseInt(iso.group(2)),
            Integer.parseInt(iso.group(3)));
      }
      if (dmy.matches()) {
        return LocalDate.of(
            Integer.parseInt(dmy.group(3)),
            Integer.parseInt(dmy.group(2)),
            Integer.parseInt(dmy.group(1)));
      }
    } catch (DateTimeException e) {
      throw new DmpException(
          DmpException.Kind.API_ERROR, "DMP returned an impossible date: " + value);
    }
    return null;
  }

  record EmployeePage(List<DmpClient.Employee> items, boolean last) {}

  /**
   * One page of GET /api/hr-employees (a Spring Page: content, totalElements, last). Only the
   * fields the plan allows are kept; birth date, phone number and gender are dropped.
   */
  static EmployeePage parseEmployeePage(JsonNode body) {
    if (body == null || !body.isObject() || !body.path("content").isArray()) {
      throw new DmpException(
          DmpException.Kind.API_ERROR, "DMP /api/hr-employees did not return a page");
    }
    JsonNode content = body.get("content");
    List<DmpClient.Employee> items = new ArrayList<>();
    for (JsonNode e : content) {
      if (!e.isObject()) {
        continue;
      }
      String employeeCode = pick(e, "employeeId", "employeeCode");
      String orgUnitCode = pick(e, "unitCode", "orgUnitCode", "departmentCode");
      if (employeeCode == null || orgUnitCode == null) {
        continue;
      }
      String name = pick(e, "fullName", "name", "employeeName");
      items.add(
          new DmpClient.Employee(
              employeeCode,
              name != null ? name : employeeCode,
              pick(e, "email"),
              orgUnitCode,
              pick(e, "costCenterCode", "costCenter"),
              toDate(pick(e, "startWDate", "startDate")),
              toDate(pick(e, "endWDate", "endDate"))));
    }
    JsonNode totalPages = body.get("totalPages");
    JsonNode number = body.get("number");
    boolean last =
        body.path("last").isBoolean() && body.get("last").asBoolean()
            || content.isEmpty()
            || (totalPages != null
                && totalPages.isNumber()
                && number != null
                && number.isNumber()
                && number.asDouble() + 1 >= totalPages.asDouble());
    return new EmployeePage(List.copyOf(items), last);
  }
}
