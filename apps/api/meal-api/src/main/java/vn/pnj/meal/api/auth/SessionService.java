package vn.pnj.meal.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.config.AppProperties;
import vn.pnj.meal.api.db.AuthSession;
import vn.pnj.meal.api.db.AuthSessionRepository;
import vn.pnj.meal.api.db.Enums.AuthMethod;
import vn.pnj.meal.api.db.Ids;

/**
 * Postgres-backed sessions behind an httpOnly cookie: 32 random bytes in the cookie, only their
 * SHA-256 in the database. Expiry is judged by {@link ClockService}, so fake time ages sessions.
 */
@Service
public class SessionService {
  public static final String COOKIE = "meal_session";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final AuthSessionRepository sessions;
  private final ClockService clock;
  private final AppProperties properties;

  public SessionService(
      AuthSessionRepository sessions, ClockService clock, AppProperties properties) {
    this.sessions = sessions;
    this.clock = clock;
    this.properties = properties;
  }

  public record Created(String id, String token, Instant expiresAt) {}

  public record Active(String id, String employeeId, AuthMethod method) {}

  public boolean secureCookies() {
    return properties.cookieSecure();
  }

  public static String hashToken(String token) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(sha.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Transactional
  public Created create(String employeeId, AuthMethod method, String userAgent) {
    Instant now = clock.now();
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    long ttlMillis = Math.round(properties.sessionTtlHours() * 3_600_000);
    Instant expiresAt = now.plusMillis(ttlMillis);
    // Housekeeping: expired sessions are not business data.
    sessions.deleteExpired(now);
    String id = Ids.newId(now.toEpochMilli());
    String agent =
        userAgent == null ? null : userAgent.substring(0, Math.min(255, userAgent.length()));
    sessions.save(new AuthSession(id, employeeId, hashToken(token), method, expiresAt, agent));
    return new Created(id, token, expiresAt);
  }

  public Optional<Active> findActive(String token) {
    return sessions
        .findByTokenHash(hashToken(token))
        .filter(s -> s.getExpiresAt().isAfter(clock.now()))
        .map(s -> new Active(s.getId(), s.getEmployeeId(), s.getMethod()));
  }

  @Transactional
  public void revoke(String sessionId) {
    sessions.deleteById(sessionId);
  }

  /** Signs the employee out everywhere, except {@code keepSessionId} when given. */
  @Transactional
  public void revokeAllForEmployee(String employeeId, String keepSessionId) {
    if (keepSessionId == null) {
      sessions.deleteByEmployee(employeeId);
    } else {
      sessions.deleteByEmployeeExcept(employeeId, keepSessionId);
    }
  }

  public void setCookie(HttpHeaders headers, String token, Instant expiresAt) {
    long seconds = Math.max(0, Duration.between(clock.now(), expiresAt).toSeconds());
    headers.add(HttpHeaders.SET_COOKIE, cookie(token).maxAge(seconds).build().toString());
  }

  public void clearCookie(HttpHeaders headers) {
    headers.add(HttpHeaders.SET_COOKIE, cookie("").maxAge(0).build().toString());
  }

  private ResponseCookie.ResponseCookieBuilder cookie(String value) {
    return ResponseCookie.from(COOKIE, value)
        .httpOnly(true)
        .sameSite("Lax")
        .secure(properties.cookieSecure())
        .path("/");
  }
}
