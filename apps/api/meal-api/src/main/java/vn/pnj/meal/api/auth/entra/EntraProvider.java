package vn.pnj.meal.api.auth.entra;

import java.util.Map;

/**
 * Entra ID sign-in (plan section 5). Two implementations: the real OIDC Authorization Code + PKCE
 * flow, and a mock for development until the app registration exists.
 */
public interface EntraProvider {
  /** Data the callback needs back from the browser, kept in a signed short-lived cookie. */
  record Transaction(String state, String nonce, String codeVerifier) {}

  /**
   * @param redirectUrl where to send the browser
   */
  record Start(String redirectUrl, Transaction transaction) {}

  /** The id_token claims the email mapping reads. */
  record Claims(Object email, Object preferredUsername) {}

  boolean isMock();

  Start start();

  /** Validates the callback and returns the id_token claims. Throws on any failure. */
  Claims complete(Map<String, String> query, Transaction transaction) throws Exception;
}
