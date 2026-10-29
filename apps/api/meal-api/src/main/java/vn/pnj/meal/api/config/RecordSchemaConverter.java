package vn.pnj.meal.api.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Makes the OpenAPI description say exactly what a response record's JSON holds, so the web's types
 * can be generated from it (J3). Every component is required; one marked {@link Nullable} is {@code
 * T | null}, or left out when the record is {@code @JsonInclude(NON_NULL)}.
 *
 * <p>Request bodies are left to the validation annotations (springdoc reads {@code @NotNull}): a
 * record with any {@code jakarta.validation} constraint is treated as a request body.
 */
@Component
public class RecordSchemaConverter implements ModelConverter {
  /** Schema names are simple class names; two classes with one name would silently merge. */
  private final Map<String, Class<?>> named = new ConcurrentHashMap<>();

  private final Set<String> clashes = ConcurrentHashMap.newKeySet();

  /** Schema names claimed by two classes; OpenApiTest requires none (rename one with @Schema). */
  public Set<String> clashes() {
    return Set.copyOf(clashes);
  }

  @Override
  public Schema<?> resolve(
      AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
    if (!chain.hasNext()) {
      return null;
    }
    Schema<?> schema = chain.next().resolve(type, context, chain);
    Class<?> raw = TypeFactory.defaultInstance().constructType(type.getType()).getRawClass();
    String ref = schema == null ? null : schema.get$ref();
    String name =
        schema == null
            ? null
            : ref != null ? ref.replace("#/components/schemas/", "") : schema.getName();
    if (name != null) {
      Class<?> other = named.putIfAbsent(name, raw);
      if (other != null && other != raw) {
        clashes.add(name + ": " + other.getName() + " and " + raw.getName());
      }
    }
    if (schema == null || !raw.isRecord() || isRequestBody(raw)) {
      return schema;
    }
    Schema<?> model = ref == null ? schema : context.getDefinedModels().get(name);
    if (model == null || model.getProperties() == null) {
      return schema;
    }
    JsonInclude include = raw.getAnnotation(JsonInclude.class);
    boolean omitsNulls = include != null && include.value() == JsonInclude.Include.NON_NULL;
    for (RecordComponent component : raw.getRecordComponents()) {
      String field = component.getName();
      Schema<?> property = model.getProperties().get(field);
      if (property == null) {
        continue;
      }
      boolean nullable = component.getAnnotatedType().isAnnotationPresent(Nullable.class);
      if (nullable && omitsNulls) {
        continue;
      }
      if (model.getRequired() == null || !model.getRequired().contains(field)) {
        model.addRequiredItem(field);
      }
      if (nullable) {
        model.getProperties().put(field, orNull(property));
      }
    }
    return schema;
  }

  private static boolean isRequestBody(Class<?> record) {
    return Arrays.stream(record.getRecordComponents())
        .flatMap(
            c ->
                Stream.concat(
                    Arrays.stream(c.getAccessor().getAnnotations()),
                    Arrays.stream(c.getAnnotatedType().getAnnotations())))
        .map(Annotation::annotationType)
        .anyMatch(a -> a.getPackageName().equals("jakarta.validation.constraints"));
  }

  /** {@code T | null}: a "null" type next to a plain type, or a reference in an anyOf. */
  private static Schema<?> orNull(Schema<?> property) {
    if (property.get$ref() == null && property.getTypes() != null) {
      if (property.getEnum() != null && !property.getEnum().contains(null)) {
        @SuppressWarnings("unchecked")
        Schema<Object> values = (Schema<Object>) property;
        values.addEnumItemObject(null);
      }
      property.addType("null");
      return property;
    }
    return new JsonSchema().anyOf(List.of(property, new JsonSchema().types(Set.of("null"))));
  }
}
