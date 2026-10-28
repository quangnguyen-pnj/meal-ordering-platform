package vn.pnj.meal.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import vn.pnj.meal.api.seed.SeedEmployees.Demo;
import vn.pnj.meal.api.support.IntegrationTest;

/** Port of the "Entra ID SSO (mock mode)" part of apps/api-legacy/test/auth.e2e-spec.ts. */
@DisplayName("Entra ID SSO (mock mode)")
class EntraSignInTest extends IntegrationTest {

  private record Started(String location, String state, Cookie tx) {}

  private Started start() throws Exception {
    MvcResult res =
        mvc.perform(get("/api/auth/entra/login")).andExpect(status().isFound()).andReturn();
    String location = res.getResponse().getHeader("Location");
    String state =
        UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state");
    return new Started(location, state, res.getResponse().getCookie("meal_oidc"));
  }

  private MvcResult callback(String state, String email, Cookie tx) throws Exception {
    var request =
        get("/api/auth/entra/callback").queryParam("state", state).queryParam("mock_email", email);
    if (tx != null) {
      request = request.cookie(tx);
    }
    return mvc.perform(request).andExpect(status().isFound()).andReturn();
  }

  @Test
  @DisplayName("maps the email case-insensitively and signs in without a PIN change")
  void mapsEmail() throws Exception {
    resetEmployee(Demo.EMPLOYEE); // PIN is still temporary
    Started s = start();
    assertThat(s.location()).contains("/api/auth/entra/mock-authorize");
    assertThat(s.tx().isHttpOnly()).isTrue();
    assertThat(s.tx().getPath()).isEqualTo("/api/auth/entra");

    MvcResult cb = callback(s.state(), "AN.Nguyen@PNJP.test", s.tx());
    assertThat(cb.getResponse().getHeader("Location")).isEqualTo("/today");
    Cookie session = cb.getResponse().getCookie("meal_session");
    mvc.perform(get("/api/auth/me").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.employeeCode").value(Demo.EMPLOYEE))
        // SSO sessions are not asked for a PIN.
        .andExpect(jsonPath("$.user.pinMustChange").value(false));
  }

  @Test
  @DisplayName("sends an unknown email to the \"account not linked\" screen")
  void notLinked() throws Exception {
    Started s = start();
    MvcResult cb = callback(s.state(), "nobody@elsewhere.test", s.tx());
    assertThat(cb.getResponse().getHeader("Location")).isEqualTo("/auth/not-linked");
    assertThat(cb.getResponse().getCookie("meal_session")).isNull();
  }

  @Test
  @DisplayName("rejects a callback without the signed transaction cookie, or with a wrong state")
  void rejects() throws Exception {
    Started s = start();
    assertThat(callback(s.state(), "an.nguyen@pnjp.test", null).getResponse().getHeader("Location"))
        .isEqualTo("/login?error=sso_failed");
    assertThat(
            callback("forged", "an.nguyen@pnjp.test", s.tx()).getResponse().getHeader("Location"))
        .isEqualTo("/login?error=sso_failed");
    Cookie tampered = new Cookie("meal_oidc", s.tx().getValue().replaceAll("\\.[^.]*$", ".AAAA"));
    assertThat(
            callback(s.state(), "an.nguyen@pnjp.test", tampered)
                .getResponse()
                .getHeader("Location"))
        .isEqualTo("/login?error=sso_failed");
  }

  @Test
  @DisplayName("an expired transaction cookie is refused")
  void expired() throws Exception {
    Started s = start();
    clock.setFakeTime(clock.now().plusSeconds(11 * 60));
    assertThat(
            callback(s.state(), "an.nguyen@pnjp.test", s.tx()).getResponse().getHeader("Location"))
        .isEqualTo("/login?error=sso_failed");
  }

  @Test
  @DisplayName("serves the fake Microsoft page only in mock mode, without reflecting markup")
  void mockPage() throws Exception {
    String html =
        mvc.perform(get("/api/auth/entra/mock-authorize").queryParam("state", "abc\"><script>"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html).contains("value=\"abcscript\"").doesNotContain("<script>");
  }
}
