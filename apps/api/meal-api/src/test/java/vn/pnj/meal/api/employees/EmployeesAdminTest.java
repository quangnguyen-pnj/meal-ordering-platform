package vn.pnj.meal.api.employees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import vn.pnj.meal.api.seed.SeedEmployees.Demo;
import vn.pnj.meal.api.support.IntegrationTest;

/** Port of the /admin/employees part of apps/api-legacy/test/admin.e2e-spec.ts. */
@DisplayName("/admin/employees: finder and pick-up floor (BR-63)")
class EmployeesAdminTest extends IntegrationTest {
  private Cookie hc;
  private Cookie deptProd;

  @BeforeEach
  void signInAll() throws Exception {
    hc = signInReady(Demo.HC_ADMIN);
    deptProd = signInReady(Demo.DEPT_ADMIN_PRODUCTION);
  }

  private int floorOverride(String code, String body) throws Exception {
    return mvc.perform(
            put("/api/employees/" + code + "/floor-override")
                .cookie(hc)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  @DisplayName("BR-63: filters by resolved floor and meal status, and shows the cost-center source")
  void filters() throws Exception {
    String t3 = read(hc, "/api/employees?floorId=fl_T3&limit=100");
    assertThat((Integer) JsonPath.read(t3, "$.total")).isPositive();
    assertThat(JsonPath.<List<String>>read(t3, "$.items[*].floorId")).containsOnly("fl_T3");
    String unassigned = read(hc, "/api/employees?mealStatus=NO_MEAL_UNIT&limit=100");
    assertThat(JsonPath.<List<String>>read(unassigned, "$.items[*].employeeCode"))
        .contains(Demo.UNASSIGNED);
    String byNode = read(hc, "/api/employees?orgUnitId=ou_XCT-A1&limit=100");
    assertThat(JsonPath.<List<String>>read(byNode, "$.items[*].orgPath"))
        .isNotEmpty()
        .allMatch(p -> p.contains("Tổ Nhẫn"));
  }

  @Test
  @DisplayName("an unknown filter value is a validation error, not a crash")
  void badFilter() throws Exception {
    mvc.perform(get("/api/employees?mealStatus=NOPE").cookie(hc))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/employees?limit=500").cookie(hc)).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName(
      "BR-63: cost center follows the org tree; the HC admin sets a pick-up floor with a reason,"
          + " audited")
  void floorOverride() throws Exception {
    String other = "PNJP000868";
    Map<String, Object> before = JsonPath.read(read(hc, "/api/employees?q=" + other), "$.items[0]");
    assertThat(before)
        .containsEntry("costCenterSource", "MAPPING")
        .containsEntry("floorSource", "MEAL_UNIT");

    assertThat(floorOverride(other, "{'floorId':'fl_T1'}")).isEqualTo(400); // a reason is required
    assertThat(floorOverride(other, "{'floorId':'fl_T1','reason':'Ngồi ở tầng 1'}")).isEqualTo(200);
    Map<String, Object> moved = JsonPath.read(read(hc, "/api/employees?q=" + other), "$.items[0]");
    assertThat(moved)
        .containsEntry("floorId", "fl_T1")
        .containsEntry("floorSource", "OVERRIDE")
        .containsEntry("floorOverrideId", "fl_T1")
        .containsEntry(
            "costCenterId", before.get("costCenterId")); // the floor never moves the cost
    assertThat(
            (Integer) JsonPath.read(read(hc, "/api/employees?floorId=fl_T1&q=" + other), "$.total"))
        .isEqualTo(1);

    assertThat(floorOverride(other, "{'floorId':null,'reason':'Về lại tầng của đơn vị'}"))
        .isEqualTo(200);
    Map<String, Object> back = JsonPath.read(read(hc, "/api/employees?q=" + other), "$.items[0]");
    assertThat(back)
        .containsEntry("floorId", before.get("floorId"))
        .containsEntry("floorSource", "MEAL_UNIT");

    List<String> after =
        jdbc.sql(
                """
                SELECT after::text FROM "AuditLog"
                WHERE action = 'employee.floorOverride.set' AND reason = 'Ngồi ở tầng 1'
                  AND "entityId" = :id
                """)
            .param("id", employeeId(other))
            .query(String.class)
            .list();
    assertThat(after).containsExactly("{\"floorCode\": \"T1\"}");
  }

  @Test
  @DisplayName("an unknown or inactive floor is refused")
  void unknownFloor() throws Exception {
    assertThat(floorOverride("PNJP000868", "{'floorId':'fl_NOPE','reason':'x'}")).isEqualTo(400);
  }

  @Test
  @DisplayName("Khối / Trung tâm / Phòng ban filter options follow the scope")
  void orgUnitOptions() throws Exception {
    List<Map<String, Object>> all = JsonPath.read(read(hc, "/api/employees/org-units"), "$.items");
    assertThat(all.getFirst()).containsEntry("level", 1).containsEntry("parentId", null);
    assertThat(all).anyMatch(o -> "Khối".equals(o.get("levelName")));
    List<Map<String, Object>> mine =
        JsonPath.read(read(deptProd, "/api/employees/org-units"), "$.items");
    assertThat(mine.size()).isLessThan(all.size());
    assertThat(mine.getFirst()).containsEntry("id", "ou_XCT");
  }

  @Test
  @DisplayName("a dept admin finds employees in scope only and may not set the floor")
  void deptAdmin() throws Exception {
    assertThat(
            JsonPath.<List<String>>read(
                read(deptProd, "/api/employees?limit=100"), "$.items[*].orgPath"))
        .allMatch(p -> p.contains("Xưởng Chế tác"));
    mvc.perform(
            put("/api/employees/" + Demo.EMPLOYEE + "/floor-override")
                .cookie(deptProd)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("{'floorId':null,'reason':'x'}")))
        .andExpect(status().isForbidden());
  }
}
