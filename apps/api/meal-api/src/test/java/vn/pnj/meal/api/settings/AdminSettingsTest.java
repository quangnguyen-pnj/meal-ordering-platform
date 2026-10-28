package vn.pnj.meal.api.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import vn.pnj.meal.api.jobs.Jobs;
import vn.pnj.meal.api.seed.SeedEmployees.Demo;
import vn.pnj.meal.api.support.IntegrationTest;

/**
 * Port of the settings, supplier, master-data, BR-06 and 403 parts of
 * apps/api-legacy/test/admin.e2e-spec.ts (the employee and role parts are in EmployeesAdminTest and
 * SystemRolesTest).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminSettingsTest extends IntegrationTest {
  @Autowired Jobs jobs;

  private Cookie hc;
  private Cookie sys;
  private Cookie employee;
  private Cookie deptProd;

  @BeforeEach
  void signInAll() throws Exception {
    hc = signInReady(Demo.HC_ADMIN);
    sys = signInReady(Demo.SYS_ADMIN);
    employee = signInReady("PNJP000100");
    deptProd = signInReady(Demo.DEPT_ADMIN_PRODUCTION);
  }

  @AfterAll
  void cleanUp() {
    // The seed tests count floors and cost centers.
    jdbc.sql("DELETE FROM \"Floor\" WHERE code = 'T9-TEST'").update();
    jdbc.sql("DELETE FROM \"CostCenter\" WHERE code = 'CC-TEST'").update();
    jdbc.sql(
            """
            UPDATE "Setting" SET value = '24'
            WHERE "factoryId" = 'fac_gv' AND key = 'registration.latePublishHours'
            """)
        .update();
  }

  // --- /admin/settings: plan 6.4 keys ---------------------------------------------------------

  @Test
  @Order(1)
  @DisplayName("lists every key with its value and default")
  void list() throws Exception {
    String res = read(hc, "/api/admin/settings");
    assertThat(JsonPath.<List<String>>read(res, "$.items[*].key"))
        .containsExactlyElementsOf(
            Arrays.stream(SettingKey.values()).map(SettingKey::code).toList());
    Map<String, Object> close =
        JsonPath.<List<Map<String, Object>>>read(res, "$.items[?(@.key == 'registration.close')]")
            .getFirst();
    assertThat(close)
        .containsEntry("value", Map.of("weekday", 4, "time", "23:59"))
        .containsEntry("defaultValue", Map.of("weekday", 4, "time", "23:59"));
  }

  @Test
  @Order(2)
  @DisplayName("validates each value against its key, stores it and audits before / after")
  void validateAndAudit() throws Exception {
    expect(
        400,
        hc,
        HttpMethod.PUT,
        "/api/admin/settings/registration.latePublishHours",
        "{'value':0}");
    expect(400, hc, HttpMethod.PUT, "/api/admin/settings/day.lockTime", "{'value':'8h'}");
    expect(404, hc, HttpMethod.PUT, "/api/admin/settings/not.a.key", "{'value':1}");
    String res =
        expect(
            200,
            sys,
            HttpMethod.PUT,
            "/api/admin/settings/registration.latePublishHours",
            "{'value':12}");
    assertThat(JsonPath.<Integer>read(res, "$.value")).isEqualTo(12);
    assertThat(JsonPath.<String>read(res, "$.updatedAt")).endsWith("Z");
    Map<String, Object> audit =
        jdbc.sql(
                """
                SELECT before::text, after::text FROM "AuditLog"
                WHERE action = 'setting.updated' AND "entityId" = 'fac_gv:registration.latePublishHours'
                ORDER BY at DESC, id DESC LIMIT 1
                """)
            .query()
            .singleRow();
    assertThat(audit).containsEntry("before", "24").containsEntry("after", "12");
  }

  @Test
  @Order(3)
  @DisplayName("a weekday-time value keeps only its two fields, like the legacy Zod parse")
  void normalized() throws Exception {
    String res =
        expect(
            200,
            hc,
            HttpMethod.PUT,
            "/api/admin/settings/menu.publishWarnAt",
            "{'value':{'weekday':1,'time':'12:00','extra':true}}");
    assertThat(JsonPath.<Map<String, Object>>read(res, "$.value"))
        .isEqualTo(Map.of("weekday", 1, "time", "12:00"));
  }

  @Test
  @Order(4)
  @DisplayName("caterer name and unit price are business settings too")
  void supplier() throws Exception {
    String before = read(hc, "/api/admin/supplier");
    String name = JsonPath.read(before, "$.name");
    Number price = JsonPath.read(before, "$.unitPrice");
    String res =
        expect(
            200,
            hc,
            HttpMethod.PUT,
            "/api/admin/supplier",
            "{'name':'" + name + "','unitPrice':36000}");
    assertThat(res).contains("\"unitPrice\":36000");
    expect(
        400, hc, HttpMethod.PUT, "/api/admin/supplier", "{'name':'" + name + "','unitPrice':-1}");
    expect(
        200,
        hc,
        HttpMethod.PUT,
        "/api/admin/supplier",
        "{'name':'" + name + "','unitPrice':" + price + "}");
  }

  // --- /admin/master: canteens, floors, cost centers (BR-62) ----------------------------------

  @Test
  @Order(10)
  @DisplayName("BR-62: adds, edits and deactivates; codes are unique; nothing is deleted")
  void floors() throws Exception {
    String created =
        expect(
            201,
            hc,
            HttpMethod.POST,
            "/api/admin/master/floors",
            "{'code':'T9-TEST','name':'Tầng thử','sortOrder':9}");
    String id = JsonPath.read(created, "$.id");
    expect(
        409, hc, HttpMethod.POST, "/api/admin/master/floors", "{'code':'T9-TEST','name':'Trùng'}");
    expect(
        200,
        hc,
        HttpMethod.PATCH,
        "/api/admin/master/floors/" + id,
        "{'name':'Tầng thử 2','active':false}");
    Map<String, Object> floor =
        JsonPath.<List<Map<String, Object>>>read(
                read(hc, "/api/admin/master/floors"), "$.items[?(@.code == 'T9-TEST')]")
            .getFirst();
    assertThat(floor)
        .containsEntry("name", "Tầng thử 2")
        .containsEntry("active", false)
        .containsEntry("usageCount", 0)
        .containsEntry("sortOrder", 9)
        .doesNotContainKey("source");
    assertThat(
            jdbc.sql("SELECT action FROM \"AuditLog\" WHERE \"entityId\" = :id ORDER BY action")
                .param("id", id)
                .query(String.class)
                .list())
        .containsExactly("floor.created", "floor.updated");
  }

  @Test
  @Order(11)
  @DisplayName("BR-62: an item still mapped to org nodes cannot be deactivated")
  void inUse() throws Exception {
    String res =
        expect(409, hc, HttpMethod.PATCH, "/api/admin/master/floors/fl_T1", "{'active':false}");
    assertThat(JsonPath.<String>read(res, "$.code")).isEqualTo("IN_USE");
    assertThat(JsonPath.<Integer>read(res, "$.details.usageCount")).isPositive();
    String cc =
        expect(
            201,
            hc,
            HttpMethod.POST,
            "/api/admin/master/cost-centers",
            "{'code':'CC-TEST','name':'Thử'}");
    Map<String, Object> row =
        JsonPath.<List<Map<String, Object>>>read(
                read(hc, "/api/admin/master/cost-centers"),
                "$.items[?(@.id == '" + JsonPath.read(cc, "$.id") + "')]")
            .getFirst();
    assertThat(row).containsEntry("source", "MANUAL").doesNotContainKey("sortOrder");
    expect(
        400, hc, HttpMethod.POST, "/api/admin/master/canteens", "{'code':'bad code!','name':'X'}");
  }

  // --- BR-06: "menu not published" warning ----------------------------------------------------

  @Test
  @Order(20)
  @DisplayName("BR-06: the HC admin sees it on every screen from Monday 12:00; employees never do")
  void warning() throws Exception {
    // Week 2028-01-10 has no menu; its warning starts Monday 2028-01-03 12:00.
    setVnTime("2028-01-03", "11:59");
    signInAll();
    assertThat(
            JsonPath.<List<Object>>read(
                read(hc, "/api/me/banners"), "$.items[?(@.kind == 'MENU_NOT_PUBLISHED')]"))
        .isEmpty();

    setVnTime("2028-01-03", "12:00");
    List<Map<String, Object>> due = JsonPath.read(read(hc, "/api/me/banners"), "$.items");
    assertThat(due)
        .contains(
            Map.of(
                "id", "menu-not-published:ct_gv:2028-01-10",
                "severity", "warning",
                "kind", "MENU_NOT_PUBLISHED",
                "params", Map.of("canteen", "Nhà ăn Gò Vấp", "weekStart", "2028-01-10")));
    assertThat(
            JsonPath.<List<Object>>read(
                read(employee, "/api/me/banners"), "$.items[?(@.kind == 'MENU_NOT_PUBLISHED')]"))
        .isEmpty();
    assertThat(jobs.menuPublishWarning()).isEqualTo(1);
  }

  @Test
  @Order(21)
  @DisplayName("BR-06: the warning reads menu.publishWarnAt from settings")
  void warningFromSetting() throws Exception {
    setVnTime("2028-01-03", "12:00");
    signInAll();
    expect(
        200,
        hc,
        HttpMethod.PUT,
        "/api/admin/settings/menu.publishWarnAt",
        "{'value':{'weekday':2,'time':'09:00'}}");
    assertThat(
            JsonPath.<List<Object>>read(
                read(hc, "/api/me/banners"), "$.items[?(@.kind == 'MENU_NOT_PUBLISHED')]"))
        .isEmpty();
    expect(
        200,
        hc,
        HttpMethod.PUT,
        "/api/admin/settings/menu.publishWarnAt",
        "{'value':{'weekday':1,'time':'12:00'}}");
  }

  // --- 403 on every settings, supplier and system endpoint ------------------------------------

  private static final List<Endpoint> SETTINGS =
      List.of(
          Endpoint.get("/api/admin/settings"),
          Endpoint.of(HttpMethod.PUT, "/api/admin/settings/day.lockTime", "{'value':'08:00'}"),
          Endpoint.get("/api/admin/supplier"),
          Endpoint.of(HttpMethod.PUT, "/api/admin/supplier", "{'name':'X','unitPrice':1}"));
  private static final List<Endpoint> SYSTEM_ONLY =
      List.of(
          Endpoint.get("/api/system/roles"),
          Endpoint.of(
              HttpMethod.POST,
              "/api/system/roles",
              "{'employeeCode':'PNJP000001','role':'HC_ADMIN'}"),
          Endpoint.of(HttpMethod.POST, "/api/system/roles/x/revoke", null));

  @Test
  @Order(30)
  @DisplayName("an employee and a dept admin get 403")
  void forbidden() throws Exception {
    expectForbidden(
        List.of(employee, deptProd),
        java.util.stream.Stream.concat(SETTINGS.stream(), SYSTEM_ONLY.stream()).toList());
  }

  @Test
  @Order(31)
  @DisplayName("the HC admin gets 403 on role assignment (system admins only)")
  void hcNotOnRoles() throws Exception {
    expectForbidden(List.of(hc), SYSTEM_ONLY);
  }
}
