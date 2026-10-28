package vn.pnj.meal.api.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.api.support.IntegrationTest;

/** The OpenAPI description the web's types will be generated from (J3). */
class OpenApiTest extends IntegrationTest {

  @Test
  @DisplayName("describes the API under /api/openapi.json, with the real /api paths")
  void describes() throws Exception {
    String body =
        mvc.perform(get("/api/openapi.json"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body)
        .contains("\"/api/auth/pin/login\"", "\"/api/employees\"", "\"/api/system/roles\"")
        .doesNotContain("\"/api/api/");
  }
}
