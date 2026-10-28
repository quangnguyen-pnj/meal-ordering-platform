package vn.pnj.meal.api.dmp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import vn.pnj.meal.api.seed.SeedEmployees;
import vn.pnj.meal.rules.VnTime;

/** Port of apps/api-legacy/src/dmp/mock/mock-dmp.client.spec.ts. */
@DisplayName("MockDmpClient")
class MockDmpClientTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final LocalDate DAY = LocalDate.of(2026, 10, 19);

  private static MockDmpClient dmp(String scenario) {
    return new MockDmpClient(scenario, JSON);
  }

  private static String hhmm(DmpClient.AttendanceRecord r) {
    return VnTime.toVn(r.checkInAt()).toLocalTime().toString();
  }

  @Test
  @DisplayName("ships every scenario the plan lists")
  void scenarios() {
    assertThat(MockDmpClient.scenarioNames(JSON))
        .contains(
            "on-time",
            "late",
            "forgot-to-check-in",
            "morning-leave",
            "afternoon-leave",
            "api-error",
            "too-little-data");
  }

  @Test
  @DisplayName("rejects an unknown scenario with the list of valid ones")
  void unknown() {
    assertThatThrownBy(() -> dmp("nope")).hasMessageContaining("Available: on-time");
  }

  @Test
  @DisplayName("serves the seed org tree (6 levels, one root) and 1,700 employees")
  void tree() {
    List<DmpClient.OrgNode> tree = dmp("on-time").getOrgTree();
    assertThat(tree.stream().filter(n -> n.parentCode() == null)).hasSize(1);
    Map<String, String> parent = new HashMap<>();
    tree.forEach(n -> parent.put(n.code(), n.parentCode()));
    int maxDepth = 0;
    for (DmpClient.OrgNode n : tree) {
      int depth = 1;
      for (String p = n.parentCode(); p != null; p = parent.get(p)) {
        depth++;
      }
      maxDepth = Math.max(maxDepth, depth);
    }
    assertThat(maxDepth).isEqualTo(6);
    assertThat(dmp("on-time").getEmployees()).hasSize(SeedEmployees.COUNT);
  }

  @Test
  @DisplayName("on-time: everyone checks in at 07:45 Vietnam time")
  void onTime() {
    List<DmpClient.AttendanceRecord> records = dmp("on-time").getAttendance(DAY);
    assertThat(records).hasSize(SeedEmployees.COUNT);
    assertThat(records.stream().map(MockDmpClientTest::hhmm).collect(Collectors.toSet()))
        .containsExactly("07:45");
  }

  @Test
  @DisplayName("late: the affected range checks in after the 08:10 cutoff")
  void late() {
    Map<String, String> byCode = new HashMap<>();
    dmp("late").getAttendance(DAY).forEach(r -> byCode.put(r.employeeCode(), hhmm(r)));
    assertThat(byCode.get("PNJP000008")).isEqualTo("08:15");
    assertThat(byCode.get("PNJP000007")).isEqualTo("07:45");
  }

  @Test
  @DisplayName("forgot-to-check-in: the affected range has no record")
  void forgot() {
    List<DmpClient.AttendanceRecord> records = dmp("forgot-to-check-in").getAttendance(DAY);
    assertThat(records).hasSize(SeedEmployees.COUNT - 13);
    assertThat(records).noneMatch(r -> r.employeeCode().equals("PNJP000010"));
  }

  @Test
  @DisplayName("too-little-data: only 30% of employees have attendance")
  void tooLittle() {
    assertThat(dmp("too-little-data").getAttendance(DAY))
        .hasSize((int) Math.floor(SeedEmployees.COUNT * 0.3));
  }

  @Test
  @DisplayName("morning and afternoon leave produce AM and PM flags for every day in range")
  void leave() {
    List<DmpClient.LeaveRecord> am = dmp("morning-leave").getLeave(DAY, DAY.plusDays(1));
    List<DmpClient.LeaveRecord> pm = dmp("afternoon-leave").getLeave(DAY, DAY);
    assertThat(am).hasSize(13 * 2);
    Set<DmpClient.LeaveDayType> amTypes = new HashSet<>();
    am.forEach(l -> amTypes.add(l.dayType()));
    assertThat(amTypes).containsExactly(DmpClient.LeaveDayType.AM);
    assertThat(pm).allMatch(l -> l.dayType() == DmpClient.LeaveDayType.PM);
  }

  @Test
  @DisplayName("api-error: attendance and leave calls fail with a DmpException")
  void apiError() {
    MockDmpClient dmp = dmp("api-error");
    assertThatThrownBy(() -> dmp.getAttendance(DAY)).isInstanceOf(DmpException.class);
    assertThatThrownBy(() -> dmp.getLeave(DAY, DAY)).isInstanceOf(DmpException.class);
  }
}
