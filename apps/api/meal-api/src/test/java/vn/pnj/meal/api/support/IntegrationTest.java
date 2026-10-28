package vn.pnj.meal.api.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Base for tests that run the whole API against a real PostgreSQL with migrations applied. */
@SpringBootTest(
    properties = {
      "meal.environment=test",
      "meal.session-secret=test-secret-test-secret-test-secret-1234",
      "meal.clock-fake-enabled=true",
      "meal.jobs-enabled=false",
      "spring.config.import="
    })
@AutoConfigureMockMvc
public abstract class IntegrationTest {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> TestDatabase.url().jdbcUrl());
    registry.add("spring.datasource.username", () -> TestDatabase.url().username());
    registry.add("spring.datasource.password", () -> TestDatabase.url().password());
  }
}
