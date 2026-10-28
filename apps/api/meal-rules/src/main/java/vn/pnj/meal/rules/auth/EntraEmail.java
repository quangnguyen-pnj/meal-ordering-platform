package vn.pnj.meal.rules.auth;

import java.util.Locale;

/**
 * Entra ID SSO mapping (plan section 5): the {@code email} claim of the id_token, falling back to
 * {@code preferred_username}, is matched case-insensitively against the employee email from DMP.
 */
public final class EntraEmail {
  private EntraEmail() {}

  public static String normalize(String value) {
    return value.trim().toLowerCase(Locale.ROOT);
  }

  /** Normalized email taken from the claims, or null when neither claim holds an email. */
  public static String extract(Object emailClaim, Object preferredUsernameClaim) {
    String email = asEmail(emailClaim);
    return email != null ? email : asEmail(preferredUsernameClaim);
  }

  private static String asEmail(Object value) {
    if (!(value instanceof String text)) {
      return null;
    }
    String normalized = normalize(text);
    return normalized.contains("@") ? normalized : null;
  }
}
