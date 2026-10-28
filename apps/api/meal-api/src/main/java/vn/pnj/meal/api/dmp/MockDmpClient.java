package vn.pnj.meal.api.dmp;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.pnj.meal.api.seed.SeedEmployees;
import vn.pnj.meal.api.seed.SeedOrgTree;
import vn.pnj.meal.rules.VnTime;

/**
 * Serves the deterministic seed org tree and employees, plus attendance and leave generated from a
 * named scenario of {@code dmp/scenarios.json}. Used whenever DMP_MODE=mock: development, tests and
 * phases that run before a DMP service account exists.
 */
public class MockDmpClient implements DmpClient {
  private final String scenarioName;
  private final JsonNode scenario;

  public MockDmpClient(String scenarioName, JsonMapper json) {
    JsonNode scenarios = loadScenarios(json);
    if (!scenarios.has(scenarioName)) {
      List<String> names = new ArrayList<>(scenarios.propertyNames());
      throw new IllegalArgumentException(
          "Unknown DMP mock scenario \""
              + scenarioName
              + "\". Available: "
              + String.join(", ", names));
    }
    this.scenarioName = scenarioName;
    this.scenario = scenarios.get(scenarioName);
  }

  public static List<String> scenarioNames(JsonMapper json) {
    return new ArrayList<>(loadScenarios(json).propertyNames());
  }

  private static JsonNode loadScenarios(JsonMapper json) {
    try (InputStream in = MockDmpClient.class.getResourceAsStream("/dmp/scenarios.json")) {
      return json.readTree(in).get("scenarios");
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read dmp/scenarios.json", e);
    }
  }

  public String scenarioName() {
    return scenarioName;
  }

  @Override
  public List<OrgNode> getOrgTree() {
    List<SeedOrgTree.Node> nodes = SeedOrgTree.build();
    Map<String, String> codeById = new HashMap<>();
    nodes.forEach(n -> codeById.put(n.id(), n.dmpCode()));
    return nodes.stream()
        .map(
            n ->
                new OrgNode(
                    n.dmpCode(),
                    n.name(),
                    n.parentId() == null ? null : codeById.get(n.parentId()),
                    n.dmpLevelName(),
                    null))
        .toList();
  }

  @Override
  public List<Employee> getEmployees() {
    List<SeedOrgTree.Node> nodes = SeedOrgTree.build();
    Map<String, String> codeById = new HashMap<>();
    nodes.forEach(n -> codeById.put(n.id(), n.dmpCode()));
    return SeedEmployees.build(nodes).stream()
        .map(
            p ->
                new Employee(
                    p.employeeCode(),
                    p.name(),
                    p.email(),
                    codeById.getOrDefault(p.orgUnitId(), ""),
                    p.dmpCostCenterCode(),
                    LocalDate.parse(p.startDate()),
                    null))
        .toList();
  }

  @Override
  public List<AttendanceRecord> getAttendance(LocalDate date) {
    failIfScenarioErrors();
    List<String> codes = getEmployees().stream().map(Employee::employeeCode).toList();
    int limit =
        scenario.has("presentRatio")
            ? (int) Math.floor(SeedEmployees.COUNT * scenario.get("presentRatio").asDouble())
            : codes.size();
    String defaultCheckIn =
        scenario.has("defaultCheckIn") ? scenario.get("defaultCheckIn").asString() : "07:45";
    List<AttendanceRecord> records = new ArrayList<>();
    for (String code : codes.subList(0, Math.min(limit, codes.size()))) {
      JsonNode override = firstMatching(scenario.path("overrides"), code);
      String time;
      if (override != null) {
        JsonNode checkIn = override.get("checkIn");
        time = checkIn == null || checkIn.isNull() ? null : checkIn.asString();
      } else {
        time = defaultCheckIn;
      }
      if (time != null) {
        records.add(new AttendanceRecord(code, VnTime.vnToUtc(date, VnTime.parseTimeOfDay(time))));
      }
    }
    return records;
  }

  @Override
  public List<LeaveRecord> getLeave(LocalDate fromDate, LocalDate toDate) {
    failIfScenarioErrors();
    List<String> codes = getEmployees().stream().map(Employee::employeeCode).toList();
    List<LeaveRecord> records = new ArrayList<>();
    for (LocalDate day = fromDate; !day.isAfter(toDate); day = day.plusDays(1)) {
      for (String code : codes) {
        JsonNode leave = firstMatching(scenario.path("leave"), code);
        if (leave != null) {
          records.add(
              new LeaveRecord(
                  code,
                  day,
                  LeaveDayType.valueOf(leave.get("dayType").asString()),
                  leave.get("leaveTypeName").asString()));
        }
      }
    }
    return records;
  }

  private void failIfScenarioErrors() {
    if (scenario.has("error")) {
      throw new DmpException(DmpException.Kind.API_ERROR, scenario.get("error").asString());
    }
  }

  /**
   * A selector matches listed codes, or an inclusive [from, to] range. Codes are fixed-width
   * ("PNJP000008"), so string comparison orders them correctly.
   */
  private static JsonNode firstMatching(JsonNode selectors, String code) {
    for (JsonNode selector : selectors) {
      for (JsonNode listed : selector.path("employees")) {
        if (listed.asString().equals(code)) {
          return selector;
        }
      }
      JsonNode range = selector.get("range");
      if (range != null
          && code.compareTo(range.get(0).asString()) >= 0
          && code.compareTo(range.get(1).asString()) <= 0) {
        return selector;
      }
    }
    return null;
  }
}
