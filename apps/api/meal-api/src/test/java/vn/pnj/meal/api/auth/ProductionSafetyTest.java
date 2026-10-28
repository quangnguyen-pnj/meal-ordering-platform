package vn.pnj.meal.api.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import vn.pnj.meal.api.seed.SeedEmployees.Demo;
import vn.pnj.meal.api.support.IntegrationTest;

/** Port of the "production safety" part of apps/api-legacy/test/auth.e2e-spec.ts. */
@DisplayName("production safety")
class ProductionSafetyTest {

  @Nested
  @TestPropertySource(properties = "meal.clock-fake-enabled=false")
  class WithoutFakeTime extends IntegrationTest {
    @Test
    @DisplayName("has no fake-time route when fake time is not enabled")
    void noRoute() throws Exception {
      Cookie sys = signInReady(Demo.SYS_ADMIN);
      mvc.perform(get("/api/dev/clock").cookie(sys)).andExpect(status().isNotFound());
    }
  }

  @Nested
  @TestPropertySource(
      properties = {
        "meal.entra.mode=oidc",
        "meal.entra.tenant-id=tenant",
        "meal.entra.client-id=client",
        "meal.entra.client-secret=secret",
        "meal.entra.redirect-uri=https://meal.example.test/api/auth/entra/callback"
      })
  class InOidcMode extends IntegrationTest {
    @Test
    @DisplayName("keeps the mock Microsoft page out of real-OIDC mode")
    void noMockPage() throws Exception {
      mvc.perform(get("/api/auth/entra/mock-authorize").queryParam("state", "abc"))
          .andExpect(status().isNotFound());
    }
  }
}
