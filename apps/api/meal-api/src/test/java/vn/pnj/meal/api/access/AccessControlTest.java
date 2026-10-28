package vn.pnj.meal.api.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import vn.pnj.meal.api.seed.SeedEmployees.Demo;
import vn.pnj.meal.api.support.IntegrationTest;

/** Port of apps/api-legacy/test/rbac.e2e-spec.ts (plan 7.1 rule 5: 403 on out-of-scope access). */
@Import(AccessControlTest.Unannotated.class)
class AccessControlTest extends IntegrationTest {
  // A plain employee that no other test resets.
  private static final String PLAIN_EMPLOYEE = "PNJP000050";

  /** A route that forgets to declare its access rule, to prove the interceptor fails closed. */
  @TestConfiguration
  static class Unannotated {
    @RestController
    static class UnannotatedController {
      @GetMapping("/test-unannotated")
      Map<String, Boolean> forgotten() {
        return Map.of("secret", true);
      }
    }
  }

  @Autowired RequestMappingHandlerMapping mappings;

  private Cookie employee;
  private Cookie deptProd; // dept admin of Xưởng Chế tác (XCT)
  private Cookie deptJewelry; // dept admin of Xưởng Kim hoàn (XKH)
  private Cookie hc;
  private Cookie sys;
  private String inProduction; // under XCT
  private String inJewelry; // under XKH
  private String inOffice; // under KVP

  @BeforeEach
  void signInAll() throws Exception {
    employee = signInReady(PLAIN_EMPLOYEE);
    deptProd = signInReady(Demo.DEPT_ADMIN_PRODUCTION);
    deptJewelry = signInReady(Demo.DEPT_ADMIN_JEWELRY);
    hc = signInReady(Demo.HC_ADMIN);
    sys = signInReady(Demo.SYS_ADMIN);
    inProduction = someoneUnder("XCT");
    inJewelry = someoneUnder("XKH");
    inOffice = someoneUnder("KVP");
  }

  private String someoneUnder(String nodeCode) {
    return jdbc.sql(
            """
            SELECT e."employeeCode" FROM "Employee" e JOIN "OrgUnit" o ON o.id = e."orgUnitId"
            WHERE strpos(o.path, :segment) > 0 AND NOT (e."employeeCode" = ANY(:demo))
            ORDER BY e."employeeCode" LIMIT 1
            """)
        .param("segment", "/ou_" + nodeCode + "/")
        .param("demo", Demo.ALL.toArray(String[]::new))
        .query(String.class)
        .single();
  }

  private org.springframework.test.web.servlet.ResultActions reset(Cookie who, String code)
      throws Exception {
    return mvc.perform(
        post("/api/employees/" + code + "/pin/reset")
            .cookie(who)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));
  }

  @Nested
  @DisplayName("authentication")
  class Authentication {
    @ParameterizedTest(name = "401 without a session: {0} {1}")
    @CsvSource({
      "GET,/api/employees",
      "POST,/api/employees/PNJP000001/pin/reset",
      "GET,/api/system/sync-runs",
      "GET,/api/auth/me"
    })
    void unauthenticated(String method, String url) throws Exception {
      mvc.perform(request(HttpMethod.valueOf(method), url))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("keeps health public")
    void healthPublic() throws Exception {
      mvc.perform(get("/api/health")).andExpect(status().isOk());
    }
  }

  @Nested
  @DisplayName("403 on out-of-scope access: PIN reset")
  class PinResetScope {
    @Test
    @DisplayName("an employee cannot reset anyone, not even a colleague")
    void employee() throws Exception {
      reset(employee, inProduction)
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("a dept admin can reset inside assigned nodes, including child nodes")
    void deptInside() throws Exception {
      reset(deptProd, inProduction).andExpect(status().isOk());
      reset(deptProd, Demo.EMPLOYEE).andExpect(status().isOk()); // level-6 child
    }

    @Test
    @DisplayName("a dept admin gets 403 for employees outside assigned nodes")
    void deptOutside() throws Exception {
      reset(deptProd, inJewelry).andExpect(status().isForbidden());
      reset(deptProd, inOffice).andExpect(status().isForbidden());
      reset(deptJewelry, inProduction).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName(
        "a dept admin gets 403, not 404, for a code that does not exist (no existence leak)")
    void noExistenceLeak() throws Exception {
      reset(deptProd, "NOT-A-CODE").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an out-of-scope reset changes nothing and writes no audit entry")
    void changesNothing() throws Exception {
      String id = employeeId(inJewelry);
      String hashBefore = pinHash(inJewelry);
      long auditsBefore = audits(id);
      reset(deptProd, inJewelry).andExpect(status().isForbidden());
      assertThat(pinHash(inJewelry)).isEqualTo(hashBefore);
      assertThat(audits(id)).isEqualTo(auditsBefore);
    }

    @Test
    @DisplayName("the HC admin reaches the whole factory; unknown codes are 404 for them")
    void hc() throws Exception {
      reset(hc, inJewelry).andExpect(status().isOk());
      reset(hc, inOffice).andExpect(status().isOk());
      reset(hc, "NOT-A-CODE").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the system admin can reset PINs anywhere")
    void sys() throws Exception {
      reset(sys, inOffice).andExpect(status().isOk());
    }

    private String pinHash(String code) {
      return jdbc.sql("SELECT \"pinHash\" FROM \"Employee\" WHERE \"employeeCode\" = :c")
          .param("c", code)
          .query(String.class)
          .single();
    }

    private long audits(String id) {
      return jdbc.sql(
              "SELECT count(*) FROM \"AuditLog\" WHERE action = 'employee.pin.reset' AND \"entityId\" = :id")
          .param("id", id)
          .query(Long.class)
          .single();
    }
  }

  @Nested
  @DisplayName("list endpoints are filtered by scope")
  class ListScopes {
    private List<String> codes(Cookie who, String query) throws Exception {
      String body =
          mvc.perform(get("/api/employees" + query).cookie(who))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      return JsonPath.read(body, "$.items[*].employeeCode");
    }

    @Test
    @DisplayName("a dept admin sees only employees under assigned nodes")
    void deptOnly() throws Exception {
      List<String> codes = codes(deptProd, "?limit=100");
      assertThat(codes).isNotEmpty();
      long outside =
          jdbc.sql(
                  """
                  SELECT count(*) FROM "Employee" e JOIN "OrgUnit" o ON o.id = e."orgUnitId"
                  WHERE e."employeeCode" = ANY(:codes) AND strpos(o.path, '/ou_XCT/') = 0
                  """)
              .param("codes", codes.toArray(String[]::new))
              .query(Long.class)
              .single();
      assertThat(outside).isZero();
    }

    @Test
    @DisplayName("two dept admins get disjoint lists")
    void disjoint() throws Exception {
      Set<String> a = new HashSet<>(codes(deptProd, "?limit=100"));
      assertThat(codes(deptJewelry, "?limit=100")).noneMatch(a::contains);
    }

    @Test
    @DisplayName("search cannot widen the scope")
    void searchScoped() throws Exception {
      assertThat(codes(deptProd, "?q=" + inJewelry)).isEmpty();
    }

    @Test
    @DisplayName("HC and system admins see the whole factory")
    void wholeFactory() throws Exception {
      assertThat(codes(hc, "?limit=100")).hasSize(100);
      assertThat(codes(sys, "?q=PNJP0016")).isNotEmpty();
    }

    @Test
    @DisplayName("an employee has no grant for the list at all")
    void employeeNone() throws Exception {
      mvc.perform(get("/api/employees").cookie(employee)).andExpect(status().isForbidden());
    }
  }

  @Nested
  @DisplayName("403 on role-level actions")
  class RoleLevel {
    @Test
    @DisplayName("only HC and system admins read sync logs")
    void syncLogs() throws Exception {
      mvc.perform(get("/api/system/sync-runs").cookie(employee)).andExpect(status().isForbidden());
      mvc.perform(get("/api/system/sync-runs").cookie(deptProd)).andExpect(status().isForbidden());
      mvc.perform(get("/api/system/sync-runs").cookie(hc)).andExpect(status().isOk());
      mvc.perform(get("/api/system/sync-runs").cookie(sys)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("only the system admin may use the fake-time endpoint")
    void fakeTime() throws Exception {
      for (Cookie who : List.of(employee, deptProd, hc)) {
        mvc.perform(get("/api/dev/clock").cookie(who)).andExpect(status().isForbidden());
      }
      mvc.perform(get("/api/dev/clock").cookie(sys)).andExpect(status().isOk());
    }
  }

  @Nested
  @DisplayName("fail-closed guard")
  class FailClosed {
    @Test
    @DisplayName("denies a signed-in user on a route that declares no access rule")
    void denies() throws Exception {
      mvc.perform(get("/api/test-unannotated").cookie(sys))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("every route of the real controllers declares its access rule")
    void everyRouteDeclared() {
      List<String> missing = new ArrayList<>();
      int checked = 0;
      for (Map.Entry<RequestMappingInfo, HandlerMethod> e :
          mappings.getHandlerMethods().entrySet()) {
        Class<?> type = e.getValue().getBeanType();
        if (!type.getPackageName().startsWith("vn.pnj.meal.api")
            || type.getName().contains("Test")) {
          continue;
        }
        checked++;
        var method = e.getValue().getMethod();
        boolean declared =
            method.isAnnotationPresent(Public.class)
                || method.isAnnotationPresent(Authenticated.class)
                || method.isAnnotationPresent(RequirePermission.class)
                || type.isAnnotationPresent(Public.class)
                || type.isAnnotationPresent(Authenticated.class)
                || type.isAnnotationPresent(RequirePermission.class);
        if (!declared) {
          missing.add(type.getSimpleName() + "." + method.getName());
        }
      }
      assertThat(checked).isGreaterThan(10);
      assertThat(missing).isEmpty();
    }
  }
}
