package vn.pnj.meal.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import vn.pnj.meal.api.common.Times;
import vn.pnj.meal.api.seed.SeedEmployees.Demo;
import vn.pnj.meal.api.support.IntegrationTest;

/** Port of apps/api-legacy/test/auth.e2e-spec.ts. */
class AuthFlowTest extends IntegrationTest {

  private ResultActions login(String code, String pin) throws Exception {
    return mvc.perform(
        post("/api/auth/pin/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json("{'employeeCode':'%s','pin':'%s'}".formatted(code, pin))));
  }

  private ResultActions changePin(Cookie session, String current, String next) throws Exception {
    return mvc.perform(
        post("/api/auth/pin/change")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json("{'currentPin':'%s','newPin':'%s'}".formatted(current, next))));
  }

  private ResultActions me(Cookie session) throws Exception {
    return mvc.perform(get("/api/auth/me").cookie(session));
  }

  private String auditJson(String action, String entityId) {
    return jdbc.sql(
            """
            SELECT row_to_json(a)::text FROM "AuditLog" a
            WHERE action = :action AND "entityId" = :entityId ORDER BY at DESC, id DESC LIMIT 1
            """)
        .param("action", action)
        .param("entityId", entityId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  @Nested
  @DisplayName("employee code + PIN sign-in")
  class PinSignIn {
    @ParameterizedTest(name = "all 4 roles can sign in: {0}")
    @CsvSource({
      "employee,PNJP000001,EMPLOYEE",
      "dept admin,PNJP000002,DEPT_ADMIN",
      "HC admin,PNJP000003,HC_ADMIN",
      "system admin,PNJP000004,SYS_ADMIN"
    })
    void allRolesSignIn(String label, String code, String role) throws Exception {
      resetEmployee(code);
      login(code, INITIAL_PIN)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.user.employeeCode").value(code))
          .andExpect(jsonPath("$.user.roles[?(@ == 'EMPLOYEE')]").exists())
          .andExpect(jsonPath("$.user.roles[?(@ == '" + role + "')]").exists())
          // First sign-in: the seeded PIN is temporary.
          .andExpect(jsonPath("$.user.pinMustChange").value(true));
    }

    @Test
    @DisplayName("sets an httpOnly session cookie and never stores the raw token")
    void cookieAndHash() throws Exception {
      resetEmployee(Demo.EMPLOYEE);
      MvcResult res = login(Demo.EMPLOYEE, INITIAL_PIN).andExpect(status().isOk()).andReturn();
      String header =
          res.getResponse().getHeaders("Set-Cookie").stream()
              .filter(c -> c.startsWith("meal_session="))
              .findFirst()
              .orElseThrow();
      assertThat(header).containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax");
      String token = res.getResponse().getCookie("meal_session").getValue();
      List<String> hashes =
          jdbc.sql(
                  """
                  SELECT "tokenHash" FROM "AuthSession"
                  WHERE "employeeId" = (SELECT id FROM "Employee" WHERE "employeeCode" = :c)
                  """)
              .param("c", Demo.EMPLOYEE)
              .query(String.class)
              .list();
      assertThat(hashes).hasSize(1);
      assertThat(hashes.getFirst()).isNotEqualTo(token).hasSize(64);
    }

    @Test
    @DisplayName("rejects a wrong PIN and an unknown code with the same response")
    void sameResponse() throws Exception {
      resetEmployee(Demo.EMPLOYEE);
      String wrongPin =
          login(Demo.EMPLOYEE, "000000")
              .andExpect(status().isUnauthorized())
              .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
              .andReturn()
              .getResponse()
              .getContentAsString();
      String unknown =
          login("NOPE", "000000")
              .andExpect(status().isUnauthorized())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(wrongPin).isEqualTo(unknown);
    }

    @Test
    @DisplayName("rejects a malformed PIN with a validation error")
    void malformed() throws Exception {
      login(Demo.EMPLOYEE, "12ab")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("locks the account after 5 wrong PINs and unlocks after 15 minutes")
    void locks() throws Exception {
      String code = "PNJP000030";
      resetEmployee(code);
      clock.setFakeTime(OffsetDateTime.parse("2026-10-19T08:00:00+07:00").toInstant());
      for (int i = 0; i < 4; i++) {
        login(code, "000000").andExpect(status().isUnauthorized());
      }
      login(code, "000000")
          .andExpect(status().isLocked())
          .andExpect(jsonPath("$.code").value("PIN_LOCKED"))
          .andExpect(
              jsonPath("$.lockedUntil")
                  .value(Times.iso(OffsetDateTime.parse("2026-10-19T08:15:00+07:00").toInstant())));
      // Even the correct PIN is refused while locked.
      login(code, INITIAL_PIN).andExpect(status().isLocked());

      clock.setFakeTime(OffsetDateTime.parse("2026-10-19T08:15:00+07:00").toInstant());
      login(code, INITIAL_PIN).andExpect(status().isOk());
      Map<String, Object> row =
          jdbc.sql(
                  "SELECT \"pinFailedCount\", \"lockedUntil\" FROM \"Employee\" WHERE \"employeeCode\" = :c")
              .param("c", code)
              .query()
              .singleRow();
      assertThat(row.get("pinFailedCount")).isEqualTo(0);
      assertThat(row.get("lockedUntil")).isNull();
    }

    @Test
    @DisplayName("a successful sign-in resets the wrong-attempt counter")
    void resetsCounter() throws Exception {
      String code = "PNJP000031";
      resetEmployee(code);
      for (int i = 0; i < 3; i++) {
        login(code, "000000").andExpect(status().isUnauthorized());
      }
      login(code, INITIAL_PIN).andExpect(status().isOk());
      assertThat(
              jdbc.sql("SELECT \"pinFailedCount\" FROM \"Employee\" WHERE \"employeeCode\" = :c")
                  .param("c", code)
                  .query(Integer.class)
                  .single())
          .isZero();
    }

    @Test
    @DisplayName("expired sessions stop working")
    void expiry() throws Exception {
      resetEmployee(Demo.EMPLOYEE, INITIAL_PIN, false);
      Instant start = Instant.parse("2026-10-19T01:00:00Z");
      clock.setFakeTime(start);
      Cookie session = signIn(Demo.EMPLOYEE);
      me(session).andExpect(status().isOk());
      clock.setFakeTime(start.plus(Duration.ofHours(11)));
      me(session).andExpect(status().isOk());
      clock.setFakeTime(start.plus(Duration.ofHours(13))); // TTL is 12h
      me(session).andExpect(status().isUnauthorized());
    }
  }

  @Nested
  @DisplayName("forced PIN change")
  class ForcedChange {
    private final String code = Demo.EMPLOYEE;

    @Test
    @DisplayName(
        "blocks everything except change-PIN, whoami and sign-out until the PIN is changed")
    void blocks() throws Exception {
      resetEmployee(code);
      Cookie session = signIn(code);
      me(session)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.user.pinMustChange").value(true));
      mvc.perform(get("/api/employees").cookie(session))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("PIN_CHANGE_REQUIRED"));

      changePin(session, INITIAL_PIN, "654321").andExpect(status().isNoContent());

      me(session).andExpect(jsonPath("$.user.pinMustChange").value(false));
      // Employee role has no access to the finder, but it is now a normal 403, not a PIN block.
      mvc.perform(get("/api/employees").cookie(session))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("the old PIN stops working and the new one signs in")
    void newPin() throws Exception {
      resetEmployee(code);
      Cookie session = signIn(code);
      changePin(session, INITIAL_PIN, "654321").andExpect(status().isNoContent());
      login(code, INITIAL_PIN).andExpect(status().isUnauthorized());
      login(code, "654321")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.user.pinMustChange").value(false));
    }

    @Test
    @DisplayName("rejects a wrong current PIN, an unchanged PIN and malformed input")
    void rejects() throws Exception {
      resetEmployee(code);
      Cookie session = signIn(code);
      changePin(session, "999999", "654321").andExpect(status().isUnauthorized());
      changePin(session, INITIAL_PIN, INITIAL_PIN).andExpect(status().isBadRequest());
      changePin(session, INITIAL_PIN, "12").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName(
        "changing the PIN signs out the employee's other sessions and writes an audit entry")
    void signsOutOthers() throws Exception {
      resetEmployee(code);
      Cookie first = signIn(code);
      Cookie second = signIn(code);
      changePin(second, INITIAL_PIN, "654321").andExpect(status().isNoContent());
      me(first).andExpect(status().isUnauthorized());
      me(second).andExpect(status().isOk());
      String entry = auditJson("auth.pin.changed", employeeId(code));
      assertThat(entry).contains("\"entityType\":\"Employee\"").doesNotContain("654321");
    }

    @Test
    @DisplayName("sign-out ends the session")
    void signOut() throws Exception {
      resetEmployee(code, INITIAL_PIN, false);
      Cookie session = signIn(code);
      mvc.perform(post("/api/auth/logout").cookie(session)).andExpect(status().isNoContent());
      me(session).andExpect(status().isUnauthorized());
    }
  }

  @Nested
  @DisplayName("PIN reset")
  class Reset {
    @Test
    @DisplayName("issues a temporary PIN, forces a change and ends open sessions")
    void reset() throws Exception {
      String target = "PNJP000040";
      resetEmployee(target, "777777", false);
      Cookie victim = signIn(target, "777777");
      Cookie hc = signInReady(Demo.HC_ADMIN);
      String temporaryPin =
          com.jayway.jsonpath.JsonPath.read(
              mvc.perform(
                      post("/api/employees/" + target + "/pin/reset")
                          .cookie(hc)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(json("{'reason':'Quên PIN'}")))
                  .andExpect(status().isOk())
                  .andExpect(
                      jsonPath("$.temporaryPin")
                          .value(org.hamcrest.Matchers.matchesPattern("\\d{6}")))
                  .andReturn()
                  .getResponse()
                  .getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
              "$.temporaryPin");

      me(victim).andExpect(status().isUnauthorized());
      login(target, "777777").andExpect(status().isUnauthorized());
      login(target, temporaryPin)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.user.pinMustChange").value(true));

      String entry = auditJson("employee.pin.reset", employeeId(target));
      assertThat(entry).contains("\"reason\":\"Quên PIN\"").doesNotContain(temporaryPin);
    }

    @Test
    @DisplayName("clears a lock so the employee can sign in with the temporary PIN")
    void clearsLock() throws Exception {
      String target = "PNJP000041";
      resetEmployee(target);
      for (int i = 0; i < 5; i++) {
        login(target, "000000");
      }
      Cookie hc = signInReady(Demo.HC_ADMIN);
      String body =
          mvc.perform(
                  post("/api/employees/" + target + "/pin/reset")
                      .cookie(hc)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      login(target, com.jayway.jsonpath.JsonPath.read(body, "$.temporaryPin"))
          .andExpect(status().isOk());
    }
  }

  @Nested
  @DisplayName("fake-time endpoint")
  class FakeTime {
    @Test
    @DisplayName("lets only a system admin freeze time, and audits it")
    void onlySysAdmin() throws Exception {
      Cookie sys = signInReady(Demo.SYS_ADMIN);
      Cookie hc = signInReady(Demo.HC_ADMIN);
      // Stay within the session lifetime: sessions are judged against the fake clock too.
      String at = Times.iso(clock.now().plus(Duration.ofHours(1)));
      String body = json("{'at':'" + at + "'}");
      mvc.perform(
              put("/api/dev/clock")
                  .cookie(hc)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isForbidden());
      mvc.perform(
              put("/api/dev/clock")
                  .cookie(sys)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.fake").value(true))
          .andExpect(jsonPath("$.now").value(at));
      mvc.perform(get("/api/dev/clock").cookie(sys)).andExpect(jsonPath("$.fake").value(true));
      assertThat(
              jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE action = 'dev.clock.set'")
                  .query(Long.class)
                  .single())
          .isPositive();
      mvc.perform(
              put("/api/dev/clock")
                  .cookie(sys)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"at\":null}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.fake").value(false));
    }
  }
}
