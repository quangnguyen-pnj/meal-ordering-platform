package vn.pnj.meal.api.dmp;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import vn.pnj.meal.rules.VnTime;

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
  private static final Pattern TIME =
      Pattern.compile(
          "(?:^|[T ])(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?:\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?$");

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

  /**
   * A DMP date-time as a UTC instant. Values without an offset are Vietnam local time (assumption
   * A7): {@code 2026-11-03T07:45:00}, {@code 03-11-2026 07:45}, or a bare {@code 07:45[:00]} on
   * {@code day}. A value with an offset or {@code Z} keeps it. Null when absent or unreadable.
   */
  static Instant toInstant(String value, LocalDate day) {
    if (value == null) {
      return null;
    }
    Matcher time = TIME.matcher(value);
    if (!time.find()) {
      return null;
    }
    String datePart = value.substring(0, time.start()).strip();
    LocalDate date = datePart.isEmpty() ? day : toDate(datePart);
    if (date == null) {
      return null;
    }
    int hour = Integer.parseInt(time.group(1));
    int minute = Integer.parseInt(time.group(2));
    int second = time.group(3) == null ? 0 : Integer.parseInt(time.group(3));
    if (hour > 23 || minute > 59 || second > 59) {
      throw new DmpException(
          DmpException.Kind.API_ERROR, "DMP returned an impossible time: " + value);
    }
    LocalTime local = LocalTime.of(hour, minute, second);
    String offset = time.group(4);
    if (offset == null) {
      return VnTime.vnToUtc(date, local);
    }
    return date.atTime(local)
        .atOffset(java.time.ZoneOffset.of(offset.equals("Z") ? "Z" : normalizeOffset(offset)))
        .toInstant();
  }

  private static String normalizeOffset(String offset) {
    return offset.length() == 5 ? offset.substring(0, 3) + ":" + offset.substring(3) : offset;
  }

  /** A JSON true, 1, "true", "1", "Y" or a status that reads as confirmed. */
  private static boolean truthy(JsonNode value) {
    if (value == null || value.isNull()) {
      return false;
    }
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    if (value.isNumber()) {
      return value.asInt() == 1;
    }
    String text =
        value.isString() ? value.asString().strip().toUpperCase(java.util.Locale.ROOT) : "";
    return List.of("TRUE", "1", "Y", "YES", "CONFIRMED", "APPROVED").contains(text);
  }

  record Page<T>(List<T> items, boolean last) {}

  /**
   * The rows of a paged DMP response: a Spring Page ({@code content}, {@code last}, {@code
   * number}/{@code totalPages}), or a {@code data}/{@code items} wrapper, or a bare array. The last
   * page is the one that says so, is empty, or is shorter than {@code pageSize}.
   */
  private static Page<JsonNode> rows(JsonNode body, String endpoint, int pageSize) {
    JsonNode content = null;
    if (body != null && body.isArray()) {
      content = body;
    } else if (body != null && body.isObject()) {
      content = firstPresent(body, "content", "data", "items");
    }
    if (content == null || !content.isArray()) {
      throw new DmpException(
          DmpException.Kind.API_ERROR, "DMP " + endpoint + " did not return rows");
    }
    List<JsonNode> items = new ArrayList<>();
    content.forEach(items::add);
    if (body.isArray() || items.isEmpty()) {
      return new Page<>(items, true);
    }
    JsonNode totalPages = body.get("totalPages");
    JsonNode number = body.get("number");
    boolean last;
    if (body.path("last").isBoolean()) {
      last = body.get("last").asBoolean();
    } else if (totalPages != null && totalPages.isNumber() && number != null && number.isNumber()) {
      last = number.asDouble() + 1 >= totalPages.asDouble();
    } else {
      last = items.size() < pageSize;
    }
    return new Page<>(items, last);
  }

  /**
   * One page of GET /api/attendance (plan section 5): one person can have several rows. Rows of
   * another day are dropped. A row without a check-in is kept only when it carries a manager's
   * confirmation.
   *
   * <p>TODO(BR-32): the confirmation field is unknown (open question Q32); every likely name is
   * read.
   */
  static Page<DmpClient.AttendanceRecord> parseAttendancePage(
      JsonNode body, LocalDate day, int pageSize) {
    Page<JsonNode> page = rows(body, "/api/attendance", pageSize);
    List<DmpClient.AttendanceRecord> out = new ArrayList<>();
    for (JsonNode row : page.items()) {
      if (!row.isObject()) {
        continue;
      }
      String code = pick(row, "employeeId", "employeeCode");
      if (code == null) {
        continue;
      }
      LocalDate rowDate = toDate(pick(row, "workDate", "attendanceDate", "date"));
      if (rowDate != null && !rowDate.equals(day)) {
        continue;
      }
      LocalDate onDay = rowDate != null ? rowDate : day;
      Instant checkIn =
          toInstant(
              pick(row, "checkIn", "checkInTime", "checkinTime", "timeIn", "inTime", "firstIn"),
              onDay);
      boolean confirmed = false;
      for (String key :
          List.of(
              "managerConfirmed",
              "isManagerConfirmed",
              "confirmedByManager",
              "isConfirmed",
              "confirmed",
              "confirmStatus",
              "confirmationStatus")) {
        confirmed |= truthy(row.get(key));
      }
      if (checkIn != null || confirmed) {
        out.add(new DmpClient.AttendanceRecord(code, checkIn, confirmed));
      }
    }
    return new Page<>(List.copyOf(out), page.last());
  }

  /** {@code leaveDayType} FULL, AM or PM, with the likely spellings; FULL when unknown. */
  static DmpClient.LeaveDayType toLeaveDayType(String value) {
    String v = value == null ? "" : value.strip().toUpperCase(java.util.Locale.ROOT);
    if (v.equals("AM") || v.contains("MORNING") || v.contains("SANG") || v.contains("SÁNG")) {
      return DmpClient.LeaveDayType.AM;
    }
    if (v.equals("PM") || v.contains("AFTERNOON") || v.contains("CHIEU") || v.contains("CHIỀU")) {
      return DmpClient.LeaveDayType.PM;
    }
    return DmpClient.LeaveDayType.FULL;
  }

  /**
   * One page of GET /api/daily-forecast?hasLeave=true (plan section 5): restStatus, leaveTypeName,
   * leaveDayType. Flags only (BR-35). Rows without any leave information are dropped.
   */
  static Page<DmpClient.LeaveRecord> parseLeavePage(JsonNode body, int pageSize) {
    Page<JsonNode> page = rows(body, "/api/daily-forecast", pageSize);
    List<DmpClient.LeaveRecord> out = new ArrayList<>();
    for (JsonNode row : page.items()) {
      if (!row.isObject()) {
        continue;
      }
      String code = pick(row, "employeeCode", "employeeId");
      LocalDate date = toDate(pick(row, "forecastDate", "workDate", "date"));
      String dayType = pick(row, "leaveDayType", "dayType");
      String typeName = pick(row, "leaveTypeName", "restStatus");
      if (code == null || date == null || (dayType == null && typeName == null)) {
        continue;
      }
      out.add(
          new DmpClient.LeaveRecord(
              code, date, toLeaveDayType(dayType), typeName != null ? typeName : "Nghỉ phép"));
    }
    return new Page<>(List.copyOf(out), page.last());
  }
}
