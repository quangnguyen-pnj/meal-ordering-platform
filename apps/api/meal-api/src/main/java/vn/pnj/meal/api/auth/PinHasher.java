package vn.pnj.meal.api.auth;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * argon2id with the parameters of the legacy API's {@code argon2} package (16-byte salt, 32-byte
 * hash, 4 lanes, 64 MiB, 3 passes), in the same PHC string, so hashes written by either API verify
 * in the other.
 */
@Component
public class PinHasher {
  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 4, 65536, 3);

  public String hash(String pin) {
    return encoder.encode(pin);
  }

  /** False for a wrong PIN and for a hash it cannot read. */
  public boolean matches(String pin, String hash) {
    if (hash == null) {
      return false;
    }
    try {
      return encoder.matches(pin, hash);
    } catch (RuntimeException e) {
      return false;
    }
  }
}
