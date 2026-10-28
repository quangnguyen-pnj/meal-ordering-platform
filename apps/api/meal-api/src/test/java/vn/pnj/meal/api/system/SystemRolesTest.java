package vn.pnj.meal.api.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.pnj.meal.api.seed.SeedEmployees.Demo;
import vn.pnj.meal.api.support.IntegrationTest;

/** Port of the /system part of apps/api-legacy/test/admin.e2e-spec.ts. */
@DisplayName("/system: HC and system admins are assigned by system admins only")
class SystemRolesTest extends IntegrationTest {

  @Test
  @DisplayName("assigns, lists and revokes an HC admin; the last system admin cannot be revoked")
  void assignAndRevoke() throws Exception {
    Cookie sys = signInReady(Demo.SYS_ADMIN);
    String created =
        mvc.perform(
                post("/api/system/roles")
                    .cookie(sys)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json("{'employeeCode':'PNJP000110','role':'HC_ADMIN'}")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = JsonPath.read(created, "$.id");
    String list =
        mvc.perform(get("/api/system/roles").cookie(sys))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<List<String>>read(list, "$.items[*].id")).contains(id);

    Cookie newHc = signInReady("PNJP000110");
    mvc.perform(get("/api/system/sync-runs").cookie(newHc)).andExpect(status().isOk());
    mvc.perform(post("/api/system/roles/" + id + "/revoke").cookie(sys)).andExpect(status().isOk());
    mvc.perform(get("/api/system/sync-runs").cookie(newHc)).andExpect(status().isForbidden());

    List<Map<String, Object>> sysRows = JsonPath.read(list, "$.items[?(@.role == 'SYS_ADMIN')]");
    assertThat(sysRows).hasSize(1);
    mvc.perform(post("/api/system/roles/" + sysRows.getFirst().get("id") + "/revoke").cookie(sys))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  @Test
  @DisplayName("assigning twice keeps one assignment; an unknown employee is a validation error")
  void idempotentAndValidated() throws Exception {
    Cookie sys = signInReady(Demo.SYS_ADMIN);
    var assign =
        (java.util.function.Function<String, MockHttpServletRequestBuilder>)
            body ->
                post("/api/system/roles")
                    .cookie(sys)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(body));
    String first =
        mvc.perform(assign.apply("{'employeeCode':'PNJP000111','role':'HC_ADMIN'}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.created").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(assign.apply("{'employeeCode':'PNJP000111','role':'HC_ADMIN'}"))
        .andExpect(jsonPath("$.created").value(false))
        .andExpect(jsonPath("$.id").value(JsonPath.<String>read(first, "$.id")));
    mvc.perform(post("/api/system/roles/" + JsonPath.read(first, "$.id") + "/revoke").cookie(sys))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revoked").value(true));
    mvc.perform(assign.apply("{'employeeCode':'NOPE','role':'HC_ADMIN'}"))
        .andExpect(status().isBadRequest());
    mvc.perform(assign.apply("{'employeeCode':'PNJP000111','role':'DEPT_ADMIN'}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("an employee and a dept admin get 403; the HC admin gets 403 on role assignment")
  void forbidden() throws Exception {
    Cookie employee = signInReady("PNJP000100");
    Cookie deptProd = signInReady(Demo.DEPT_ADMIN_PRODUCTION);
    Cookie hc = signInReady(Demo.HC_ADMIN);
    List<String> failures = new ArrayList<>();
    for (Cookie who : List.of(employee, deptProd, hc)) {
      for (MockHttpServletRequestBuilder request :
          List.of(
              get("/api/system/roles"),
              post("/api/system/roles")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json("{'employeeCode':'PNJP000001','role':'HC_ADMIN'}")),
              post("/api/system/roles/x/revoke"))) {
        int status = mvc.perform(request.cookie(who)).andReturn().getResponse().getStatus();
        if (status != 403) {
          failures.add(status + "");
        }
      }
    }
    assertThat(failures).isEmpty();
  }
}
