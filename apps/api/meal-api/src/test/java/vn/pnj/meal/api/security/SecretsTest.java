package vn.pnj.meal.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import vn.pnj.meal.api.seed.SeedEmployees.Demo;
import vn.pnj.meal.api.support.IntegrationTest;

/**
 * Port of apps/api-legacy/test/security.e2e-spec.ts (docs/security.md): secrets never leave the API
 * in a response, and no API answer is cached. Add every new read endpoint to {@link #READS}.
 */
class SecretsTest extends IntegrationTest {
  /** Field names and values that must never appear in a response body. */
  private static final Pattern SECRET =
      Pattern.compile(
          "pinHash|tokenHash|\\$argon2|SESSION_SECRET|CLIENT_SECRET|SERVICE_PASSWORD|sessionSecret|clientSecret|servicePassword",
          Pattern.CASE_INSENSITIVE);

  private static final List<String> READS =
      List.of(
          "/api/auth/me",
          "/api/employees",
          "/api/employees?q=PNJP",
          "/api/employees/org-units",
          "/api/system/roles",
          "/api/system/sync-runs",
          "/api/dev/clock",
          "/api/openapi.json",
          "/api/me/banners",
          "/api/admin/settings",
          "/api/admin/supplier",
          "/api/admin/master/canteens",
          "/api/admin/master/floors",
          "/api/admin/master/cost-centers",
          "/api/admin/org/tree",
          "/api/admin/org/unassigned",
          "/api/admin/org/missing-cost-center",
          "/api/admin/dish-types",
          "/api/admin/dishes?canteenId=ct_gv&includeArchived=true",
          "/api/admin/desserts?canteenId=ct_gv&includeArchived=true",
          "/api/admin/menu/canteens",
          "/api/admin/menu/weeks?canteenId=ct_gv&weekStart=2027-03-01",
          "/api/menu/my-week");

  @ParameterizedTest(name = "{0}: no PIN hash, token hash or configured secret in any read")
  @ValueSource(strings = {Demo.HC_ADMIN, Demo.SYS_ADMIN, Demo.DEPT_ADMIN_PRODUCTION})
  void noSecrets(String code) throws Exception {
    Cookie session = signInReady(code);
    for (String url : READS) {
      String body =
          mvc.perform(get(url).cookie(session)).andReturn().getResponse().getContentAsString();
      Matcher leak = SECRET.matcher(body);
      assertThat(leak.find() ? url + " leaks " + leak.group() : null).isNull();
    }
  }

  @Test
  @DisplayName("a rejected sign-in does not echo the PIN back")
  void noEcho() throws Exception {
    String body =
        mvc.perform(
                post("/api/auth/pin/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json("{'employeeCode':'PNJP000001','pin':'98765x'}")))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("98765x");
  }

  @Test
  @DisplayName("errors, reads and the temporary-PIN reset are no-store and nosniff")
  void noStore() throws Exception {
    Cookie hc = signInReady(Demo.HC_ADMIN);
    List<ResultActions> answers =
        List.of(
            mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()),
            mvc.perform(get("/api/employees").cookie(hc)),
            mvc.perform(
                    post("/api/employees/PNJP000043/pin/reset")
                        .cookie(hc)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk()));
    for (ResultActions answer : answers) {
      answer
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(header().string("X-Content-Type-Options", "nosniff"))
          .andExpect(header().doesNotExist("X-Powered-By"));
    }
  }
}
