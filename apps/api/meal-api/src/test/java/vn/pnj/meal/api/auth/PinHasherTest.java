package vn.pnj.meal.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PinHasherTest {
  /** The seed PIN 123456 as hashed by the legacy API's Node argon2 package. */
  private static final String LEGACY_HASH =
      "$argon2id$v=19$m=65536,t=3,p=4$SDYdPe5ae1I4rkipLKyQ8Q$e1DzL6G92taszk2qL7+8NixM+U7z87aUnUSuQ0j5eJU";

  private final PinHasher hasher = new PinHasher();

  @Test
  @DisplayName("verifies hashes written by the legacy API")
  void legacyHash() {
    assertThat(hasher.matches("123456", LEGACY_HASH)).isTrue();
    assertThat(hasher.matches("123457", LEGACY_HASH)).isFalse();
  }

  @Test
  @DisplayName("writes argon2id with the legacy parameters, salted per hash")
  void writes() {
    String hash = hasher.hash("654321");
    assertThat(hash).startsWith("$argon2id$v=19$m=65536,t=3,p=4$");
    assertThat(hasher.hash("654321")).isNotEqualTo(hash);
    assertThat(hasher.matches("654321", hash)).isTrue();
  }

  @Test
  @DisplayName("treats a missing or unreadable hash as a wrong PIN")
  void unreadable() {
    assertThat(hasher.matches("123456", null)).isFalse();
    assertThat(hasher.matches("123456", "not-a-hash")).isFalse();
  }
}
