package vn.pnj.meal.api.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Environment configuration. {@code application.yml} maps the variables of {@code .env.example}
 * (the same names the legacy API read) onto this record. Every check runs in the constructor, so a
 * wrong configuration stops the API at startup with all problems listed at once.
 */
@ConfigurationProperties("meal")
public record AppProperties(
    Environment environment,
    URI appBaseUrl,
    String sessionSecret,
    double sessionTtlHours,
    boolean cookieSecure,
    boolean clockFakeEnabled,
    boolean jobsEnabled,
    String firstSystemAdminCode,
    Entra entra,
    Dmp dmp) {

  public enum Environment {
    DEVELOPMENT,
    TEST,
    PRODUCTION
  }

  public enum EntraMode {
    MOCK,
    OIDC
  }

  public enum DmpMode {
    MOCK,
    HTTP
  }

  /** Entra ID SSO (plan section 5). */
  public record Entra(
      EntraMode mode, String tenantId, String clientId, String clientSecret, String redirectUri) {
    public Entra {
      tenantId = blankToNull(tenantId);
      clientId = blankToNull(clientId);
      clientSecret = blankToNull(clientSecret);
      redirectUri = blankToNull(redirectUri);
    }

    @Override
    public String toString() {
      return "Entra[mode=" + mode + ", tenantId=" + tenantId + ", clientId=" + clientId + "]";
    }
  }

  /** DMP adapter (plan section 5). */
  public record Dmp(
      DmpMode mode,
      String mockScenario,
      String baseUrl,
      String serviceEmail,
      String servicePassword,
      String companyCode) {
    public Dmp {
      baseUrl = blankToNull(baseUrl);
      serviceEmail = blankToNull(serviceEmail);
      servicePassword = blankToNull(servicePassword);
      companyCode = blankToNull(companyCode);
    }

    @Override
    public String toString() {
      return "Dmp[mode=" + mode + ", scenario=" + mockScenario + ", baseUrl=" + baseUrl + "]";
    }
  }

  public AppProperties {
    firstSystemAdminCode = blankToNull(firstSystemAdminCode);
    List<String> problems =
        problems(
            environment,
            sessionSecret,
            sessionTtlHours,
            cookieSecure,
            clockFakeEnabled,
            entra,
            dmp);
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "Invalid environment configuration:\n  - " + String.join("\n  - ", problems));
    }
  }

  /** Fake time is for tests and demos only, never in production (plan P0). */
  public boolean fakeTimeAllowed() {
    return environment != Environment.PRODUCTION && clockFakeEnabled;
  }

  /** Keeps the session secret and the DMP password out of logs and error pages. */
  @Override
  public String toString() {
    return "AppProperties[environment="
        + environment
        + ", appBaseUrl="
        + appBaseUrl
        + ", cookieSecure="
        + cookieSecure
        + ", clockFakeEnabled="
        + clockFakeEnabled
        + ", jobsEnabled="
        + jobsEnabled
        + ", entra="
        + entra
        + ", dmp="
        + dmp
        + "]";
  }

  private static List<String> problems(
      Environment environment,
      String sessionSecret,
      double sessionTtlHours,
      boolean cookieSecure,
      boolean clockFakeEnabled,
      Entra entra,
      Dmp dmp) {
    List<String> problems = new ArrayList<>();
    if (environment == null) {
      problems.add("APP_ENV (or NODE_ENV) must be development, test or production");
    }
    if (sessionSecret == null || sessionSecret.length() < 32) {
      problems.add("SESSION_SECRET must be at least 32 characters");
    }
    if (!(sessionTtlHours > 0)) {
      problems.add("SESSION_TTL_HOURS must be positive");
    }
    if (entra.mode() == EntraMode.OIDC) {
      requireAll(
          problems,
          "ENTRA_MODE=oidc",
          new String[] {"ENTRA_TENANT_ID", entra.tenantId()},
          new String[] {"ENTRA_CLIENT_ID", entra.clientId()},
          new String[] {"ENTRA_CLIENT_SECRET", entra.clientSecret()},
          new String[] {"ENTRA_REDIRECT_URI", entra.redirectUri()});
    }
    if (dmp.mode() == DmpMode.HTTP) {
      requireAll(
          problems,
          "DMP_MODE=http",
          new String[] {"DMP_BASE_URL", dmp.baseUrl()},
          new String[] {"DMP_SERVICE_EMAIL", dmp.serviceEmail()},
          new String[] {"DMP_SERVICE_PASSWORD", dmp.servicePassword()},
          new String[] {"DMP_COMPANY_CODE", dmp.companyCode()});
    }
    if (environment == Environment.PRODUCTION) {
      if (clockFakeEnabled) {
        problems.add("CLOCK_FAKE_ENABLED: Fake time is not allowed in production");
      }
      if (entra.mode() == EntraMode.MOCK) {
        problems.add("ENTRA_MODE=mock is not allowed in production");
      }
      if (sessionSecret != null && sessionSecret.startsWith("change-me")) {
        problems.add("Replace the example SESSION_SECRET in production");
      }
      // The PIN and the session cookie travel in clear without HTTPS (docs/security.md).
      if (!cookieSecure) {
        problems.add("COOKIE_SECURE=true is required in production (serve over HTTPS)");
      }
    }
    return problems;
  }

  private static void requireAll(List<String> problems, String when, String[]... namedValues) {
    for (String[] namedValue : namedValues) {
      if (namedValue[1] == null) {
        problems.add(namedValue[0] + " is required when " + when);
      }
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
