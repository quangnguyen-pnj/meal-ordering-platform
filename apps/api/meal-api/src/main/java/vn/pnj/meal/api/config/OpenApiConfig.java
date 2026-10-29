package vn.pnj.meal.api.config;

import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import vn.pnj.meal.api.access.RequestPrincipal;
import vn.pnj.meal.rules.access.ListScope;
import vn.pnj.meal.rules.menu.LibraryImport;
import vn.pnj.meal.rules.org.MappingImport;

/**
 * The OpenAPI description (/api/openapi.json) the web's types are generated from. The signed-in
 * principal and the list scope are handler arguments filled by the access interceptor, not request
 * parameters, so they are left out of it. A request body read as a JsonNode is a JSON object.
 */
@Configuration
public class OpenApiConfig {
  static {
    SpringDocUtils.getConfig()
        .addRequestWrapperToIgnore(RequestPrincipal.class, ListScope.class)
        .replaceWithSchema(JsonNode.class, new JsonSchema());
  }

  /**
   * What the annotations cannot say: meal-rules has no OpenAPI annotations, so the columns its
   * import errors name are listed here, and banner parameters are strings or numbers.
   */
  @Bean
  OpenApiCustomizer ruleValues() {
    return api -> {
      @SuppressWarnings("rawtypes")
      Map<String, Schema> schemas = api.getComponents().getSchemas();
      restrict(schemas.get("LibraryImportError"), "field", LibraryImport.FIELDS);
      restrict(schemas.get("MappingImportError"), "field", MappingImport.FIELDS);
      Schema<?> banner = schemas.get("BannerView");
      if (banner != null) {
        banner
            .getProperties()
            .put(
                "params",
                new JsonSchema()
                    .types(Set.of("object"))
                    .additionalProperties(
                        new JsonSchema()
                            .anyOf(
                                List.of(
                                    new JsonSchema().types(Set.of("string")),
                                    new JsonSchema().types(Set.of("number"))))));
      }
    };
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void restrict(Schema schema, String property, List<String> values) {
    if (schema != null && schema.getProperties() != null) {
      Schema field = (Schema) schema.getProperties().get(property);
      field.setEnum(new ArrayList<>(values));
    }
  }
}
