package vn.pnj.meal.api.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Sets {@code spring.datasource.*} from {@code DATABASE_URL} unless a JDBC URL is configured
 * directly (tests do that). Runs after {@code .env} has been imported.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    String databaseUrl = environment.getProperty("DATABASE_URL");
    if (environment.containsProperty("spring.datasource.url")
        || databaseUrl == null
        || databaseUrl.isBlank()) {
      return;
    }
    DatabaseUrl parsed = DatabaseUrl.parse(databaseUrl);
    Map<String, Object> properties = new HashMap<>();
    properties.put("spring.datasource.url", parsed.jdbcUrl());
    if (parsed.username() != null) {
      properties.put("spring.datasource.username", parsed.username());
    }
    if (parsed.password() != null) {
      properties.put("spring.datasource.password", parsed.password());
    }
    environment.getPropertySources().addLast(new MapPropertySource("databaseUrl", properties));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
