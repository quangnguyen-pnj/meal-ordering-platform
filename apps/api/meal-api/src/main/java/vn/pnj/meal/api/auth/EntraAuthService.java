package vn.pnj.meal.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import vn.pnj.meal.api.auth.entra.EntraProvider;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.config.AppProperties;
import vn.pnj.meal.api.db.Employee;
import vn.pnj.meal.api.db.EmployeeRepository;
import vn.pnj.meal.api.db.Enums.AuthMethod;
import vn.pnj.meal.rules.auth.EntraEmail;

/** Microsoft sign-in: the PKCE transaction cookie, the email mapping and the session. */
@Service
public class EntraAuthService {
  public static final String TX_COOKIE = "meal_oidc";
  public static final String TX_COOKIE_PATH = "/api/auth/entra";
  public static final long TX_TTL_MILLIS = 10 * 60_000;
  private static final Logger log = LoggerFactory.getLogger(EntraAuthService.class);

  private final EntraProvider provider;
  private final AppProperties properties;
  private final ClockService clock;
  private final SessionService sessions;
  private final EmployeeRepository employees;
  private final JsonMapper json;

  public EntraAuthService(
      EntraProvider provider,
      AppProperties properties,
      ClockService clock,
      SessionService sessions,
      EmployeeRepository employees,
      JsonMapper json) {
    this.provider = provider;
    this.properties = properties;
    this.clock = clock;
    this.sessions = sessions;
    this.employees = employees;
    this.json = json;
  }

  public EntraProvider provider() {
    return provider;
  }

  public record Started(String redirectUrl, String cookieValue) {}

  public sealed interface Result {
    record Ok(SessionService.Created session) implements Result {}

    record NotLinked() implements Result {}

    record Failed() implements Result {}
  }

  public Started start() {
    EntraProvider.Start start = provider.start();
    return new Started(start.redirectUrl(), sign(start.transaction()));
  }

  public Result complete(Map<String, String> query, String cookieValue, String userAgent) {
    EntraProvider.Transaction tx = cookieValue == null ? null : verify(cookieValue);
    if (tx == null) {
      return new Result.Failed();
    }
    String email;
    try {
      EntraProvider.Claims claims = provider.complete(query, tx);
      email = EntraEmail.extract(claims.email(), claims.preferredUsername());
    } catch (Exception e) {
      log.warn("Microsoft sign-in failed: {}", e.getMessage());
      return new Result.Failed();
    }
    if (email == null) {
      return new Result.NotLinked();
    }
    // Case-insensitive match on the DMP email (plan section 5). Zero or several matches are both
    // "not linked": the HC admin or a system admin resolves them.
    List<Employee> matches = employees.findActiveByEmailIgnoreCase(email);
    if (matches.size() != 1) {
      return new Result.NotLinked();
    }
    return new Result.Ok(sessions.create(matches.getFirst().getId(), AuthMethod.ENTRA, userAgent));
  }

  private record Payload(EntraProvider.Transaction tx, long exp) {}

  /**
   * {@code base64url(json).hmac}: the PKCE verifier must not be forgeable or readable by scripts.
   */
  private String sign(EntraProvider.Transaction tx) {
    String payload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                json.writeValueAsBytes(
                    new Payload(tx, clock.now().toEpochMilli() + TX_TTL_MILLIS)));
    return payload + "." + mac(payload);
  }

  private EntraProvider.Transaction verify(String value) {
    String[] parts = value.split("\\.");
    if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
      return null;
    }
    byte[] expected = mac(parts[0]).getBytes(StandardCharsets.US_ASCII);
    byte[] actual = parts[1].getBytes(StandardCharsets.US_ASCII);
    if (!MessageDigest.isEqual(expected, actual)) {
      return null;
    }
    try {
      Payload payload = json.readValue(Base64.getUrlDecoder().decode(parts[0]), Payload.class);
      return payload.exp() > clock.now().toEpochMilli() ? payload.tx() : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private String mac(String payload) {
    try {
      Mac hmac = Mac.getInstance("HmacSHA256");
      hmac.init(
          new SecretKeySpec(
              properties.sessionSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(hmac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
