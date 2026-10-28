package vn.pnj.meal.api.auth.entra;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * Stand-in for Microsoft while no app registration exists. "Authorizing" opens a tiny local page
 * where a developer types an email; the callback treats it as the id_token email claim. Refused at
 * startup in production (see AppProperties).
 */
public class MockEntraProvider implements EntraProvider {
  private static final SecureRandom RANDOM = new SecureRandom();

  @Override
  public boolean isMock() {
    return true;
  }

  @Override
  public Start start() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    return new Start(
        "/api/auth/entra/mock-authorize?state=" + URLEncoder.encode(state, StandardCharsets.UTF_8),
        new Transaction(state, "mock", "mock"));
  }

  @Override
  public Claims complete(Map<String, String> query, Transaction transaction) {
    String state = query.get("state");
    if (state == null || !state.equals(transaction.state())) {
      throw new IllegalArgumentException("State mismatch");
    }
    String email = query.get("mock_email");
    if (email == null) {
      throw new IllegalArgumentException("mock_email is required");
    }
    return new Claims(email, null);
  }
}
