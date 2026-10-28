package vn.pnj.meal.api.common;

import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/** Keeps the JSON the web already reads from the legacy API: instants as in {@link Times}. */
@Configuration
public class JsonConfig {

  @Bean
  SimpleModule mealJsonModule() {
    return new SimpleModule("meal").addSerializer(Instant.class, new InstantSerializer());
  }

  static final class InstantSerializer extends StdSerializer<Instant> {
    InstantSerializer() {
      super(Instant.class);
    }

    @Override
    public void serialize(Instant value, JsonGenerator gen, SerializationContext ctxt) {
      gen.writeString(Times.iso(value));
    }
  }
}
