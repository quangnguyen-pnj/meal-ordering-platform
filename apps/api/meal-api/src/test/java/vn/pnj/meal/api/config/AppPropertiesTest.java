package vn.pnj.meal.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import vn.pnj.meal.api.config.AppProperties.DmpMode;
import vn.pnj.meal.api.config.AppProperties.EntraMode;
import vn.pnj.meal.api.config.AppProperties.Environment;

/** Binds application.yml with the given environment variables, as the running API does. */
class AppPropertiesTest {
  private static final Map<String, Object> BASE =
      Map.of("SESSION_SECRET", "a-very-long-secret-value-0123456789abcdef");

  private static AppProperties load(Map<String, Object> variables) {
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    Map<String, Object> merged = new HashMap<>(BASE);
    merged.putAll(variables);
    environment.getPropertySources().addFirst(new MapPropertySource("variables", merged));
    try {
      new YamlPropertySourceLoader()
          .load("application", new ClassPathResource("application.yml"))
          .forEach(environment.getPropertySources()::addLast);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return Binder.get(environment).bindOrCreate("meal", AppProperties.class);
  }

  private static void assertRejected(ThrowingCallable load, String... messages) {
    assertThatThrownBy(load)
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .message()
        .contains(messages);
  }

  @Test
  void appliesDevelopmentDefaults() {
    AppProperties properties = load(Map.of());
    assertThat(properties.environment()).isEqualTo(Environment.DEVELOPMENT);
    assertThat(properties.entra().mode()).isEqualTo(EntraMode.MOCK);
    assertThat(properties.dmp().mode()).isEqualTo(DmpMode.MOCK);
    assertThat(properties.dmp().mockScenario()).isEqualTo("on-time");
    assertThat(properties.clockFakeEnabled()).isFalse();
    assertThat(properties.cookieSecure()).isFalse();
    assertThat(properties.jobsEnabled()).isTrue();
    assertThat(properties.sessionTtlHours()).isEqualTo(12);
  }

  @Test
  void readsNodeEnvSoOneEnvFileServesWebAndApi() {
    assertThat(load(Map.of("NODE_ENV", "test")).environment()).isEqualTo(Environment.TEST);
    assertThat(load(Map.of("NODE_ENV", "test", "APP_ENV", "development")).environment())
        .isEqualTo(Environment.DEVELOPMENT);
  }

  @Test
  void refusesToStartWithoutAKnownEnvironment() {
    // An empty APP_ENV must not quietly count as "not production" and allow fake time.
    assertRejected(() -> load(Map.of("APP_ENV", "")), "APP_ENV (or NODE_ENV) must be");
  }

  @Test
  void treatsEmptyStringsFromEnvFilesAsUnset() {
    assertThat(load(Map.of("ENTRA_TENANT_ID", "")).entra().tenantId()).isNull();
    assertThat(load(Map.of("FIRST_SYSTEM_ADMIN_CODE", " ")).firstSystemAdminCode()).isNull();
  }

  @Test
  void requiresALongSessionSecret() {
    assertRejected(() -> load(Map.of("SESSION_SECRET", "short")), "SESSION_SECRET");
  }

  @Test
  void requiresEntraSettingsWhenEntraModeIsOidc() {
    assertRejected(() -> load(Map.of("ENTRA_MODE", "oidc")), "ENTRA_TENANT_ID is required");
  }

  @Test
  void requiresDmpCredentialsWhenDmpModeIsHttp() {
    assertRejected(() -> load(Map.of("DMP_MODE", "http")), "DMP_BASE_URL is required");
  }

  @Test
  void rejectsTheMockMicrosoftPageAndTheExampleSecretInProduction() {
    assertRejected(
        () ->
            load(
                Map.of(
                    "NODE_ENV", "production",
                    "SESSION_SECRET", "change-me-dev-only-change-me-dev-only")),
        "ENTRA_MODE=mock is not allowed in production",
        "Replace the example SESSION_SECRET");
  }

  @Test
  void requiresTheSecureCookieFlagSoHttpsInProduction() {
    Map<String, Object> production = new HashMap<>(productionWithOidc());
    assertRejected(() -> load(production), "COOKIE_SECURE=true is required in production");
    production.put("COOKIE_SECURE", "true");
    assertThat(load(production).cookieSecure()).isTrue();
  }

  @Test
  void rejectsFakeTimeOutrightInProduction() {
    Map<String, Object> production = new HashMap<>(productionWithOidc());
    production.put("COOKIE_SECURE", "true");
    production.put("CLOCK_FAKE_ENABLED", "true");
    assertRejected(() -> load(production), "Fake time is not allowed in production");
  }

  @Test
  void neverPrintsSecrets() {
    Map<String, Object> variables = new HashMap<>(productionWithOidc());
    variables.put("COOKIE_SECURE", "true");
    variables.put("DMP_SERVICE_PASSWORD", "dmp-password-value");
    String printed = load(variables).toString();
    assertThat(printed)
        .doesNotContain("a-real-secret")
        .doesNotContain("entra-secret-value")
        .doesNotContain("dmp-password-value");
  }

  private static Map<String, Object> productionWithOidc() {
    return Map.of(
        "NODE_ENV", "production",
        "ENTRA_MODE", "oidc",
        "ENTRA_TENANT_ID", "t",
        "ENTRA_CLIENT_ID", "c",
        "ENTRA_CLIENT_SECRET", "entra-secret-value",
        "ENTRA_REDIRECT_URI", "https://meal.pnjp.test/api/auth/entra/callback",
        "SESSION_SECRET", "a-real-secret-a-real-secret-a-real-secret");
  }
}
