package vn.pnj.meal.api.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import vn.pnj.meal.api.config.RecordSchemaConverter;
import vn.pnj.meal.api.support.IntegrationTest;

/** The OpenAPI description the web's types are generated from (J3). */
class OpenApiTest extends IntegrationTest {
  /** Committed next to the Maven modules; apps/web generates src/lib/api/openapi.d.ts from it. */
  private static final Path SNAPSHOT = Path.of("..", "openapi.json");

  @Autowired JsonMapper json;
  @Autowired RecordSchemaConverter records;

  private String document() throws Exception {
    return mvc.perform(get("/api/openapi.json"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  @DisplayName("describes the API under /api/openapi.json, with the real /api paths")
  void describes() throws Exception {
    assertThat(document())
        .contains("\"/api/auth/pin/login\"", "\"/api/employees\"", "\"/api/system/roles\"")
        .doesNotContain("\"/api/api/");
    // Schemas are named by simple class name: two classes with one name would merge into one.
    assertThat(records.clashes()).isEmpty();
  }

  /**
   * After an API change, run {@code pnpm --filter @meal/api openapi} (this test with {@code
   * -Dopenapi.write=true}), then {@code pnpm --filter @meal/web api:types}.
   */
  @Test
  @DisplayName("apps/api/openapi.json is the description the API serves")
  void snapshot() throws Exception {
    String live =
        json.writer()
                .with(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(json.readTree(document()))
            + "\n";
    if (Boolean.getBoolean("openapi.write")) {
      Files.writeString(SNAPSHOT, live);
    }
    assertThat(Files.exists(SNAPSHOT) ? Files.readString(SNAPSHOT) : "")
        .as("apps/api/openapi.json is stale: run `pnpm --filter @meal/api openapi`")
        .isEqualTo(live);
  }
}
