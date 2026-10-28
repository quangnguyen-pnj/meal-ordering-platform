package vn.pnj.meal.api.health;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.pnj.meal.api.common.ApiErrorCode;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.support.IntegrationTest;

/** The HTTP contract every endpoint shares with the legacy API: prefix, headers, error body. */
@Import(HttpBasicsTest.Probe.class)
class HttpBasicsTest extends IntegrationTest {
  @Autowired MockMvc mvc;

  @Test
  void healthAnswersOkUnderTheApiPrefix() throws Exception {
    mvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"));
    mvc.perform(get("/health")).andExpect(status().isNotFound());
  }

  @Test
  void everyAnswerCarriesTheSecurityHeaders() throws Exception {
    for (String path : new String[] {"/api/health", "/api/no-such-route"}) {
      mvc.perform(get(path))
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(header().string("X-Content-Type-Options", "nosniff"))
          .andExpect(header().string("Referrer-Policy", "same-origin"))
          .andExpect(header().doesNotExist("X-Powered-By"));
    }
  }

  @Test
  void unknownRoutesAndMethodsAreNotFound() throws Exception {
    mvc.perform(get("/api/no-such-route"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.statusCode").value(404))
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mvc.perform(post("/api/health"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void apiExceptionsKeepTheirCodeAndDetails() throws Exception {
    mvc.perform(get("/api/test-probe/locked"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.statusCode").value(409))
        .andExpect(jsonPath("$.code").value("DAY_LOCKED"))
        .andExpect(jsonPath("$.message").value("Ngày đã khóa"))
        .andExpect(jsonPath("$.details.date").value("2026-10-26"))
        .andExpect(jsonPath("$.lockedUntil").doesNotExist());
  }

  @Test
  void invalidBodiesAreValidationFailedWithThePath() throws Exception {
    mvc.perform(
            post("/api/test-probe/pin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"12ab\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.details[0].path").value("pin"))
        // docs/security.md: errors give the path and a message, never the value sent.
        .andExpect(content().string(not(containsString("12ab"))));
    mvc.perform(
            post("/api/test-probe/pin").contentType(MediaType.APPLICATION_JSON).content("{oops"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void unexpectedErrorsRevealNothing() throws Exception {
    mvc.perform(get("/api/test-probe/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL"))
        .andExpect(jsonPath("$.message").value("Internal server error"));
  }

  @TestConfiguration
  static class Probe {
    @RestController
    static class ProbeController {
      record PinBody(@Pattern(regexp = "\\d{6}") String pin) {}

      @GetMapping("/test-probe/locked")
      void locked() {
        throw new ApiException(
            HttpStatus.CONFLICT,
            ApiErrorCode.DAY_LOCKED,
            "Ngày đã khóa",
            java.util.Map.of("date", "2026-10-26"),
            null);
      }

      @PostMapping("/test-probe/pin")
      void pin(@Valid @RequestBody PinBody body) {}

      @GetMapping("/test-probe/boom")
      void boom() {
        throw new IllegalStateException("SELECT secret FROM somewhere");
      }
    }
  }
}
