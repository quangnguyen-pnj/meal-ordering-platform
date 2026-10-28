package vn.pnj.meal.rules.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AuthRulesTest {

  @Nested
  @DisplayName("PIN lock rules")
  class PinLock {
    private final Instant now = Instant.parse("2026-10-19T01:00:00Z");

    @Test
    @DisplayName("locks for lockMinutes on the 5th wrong PIN and restarts the counter")
    void locksOnFifth() {
      PinRules.FailureState state = PinRules.registerFailure(4, 5, 15, now);
      assertThat(state.failedCount()).isZero();
      assertThat(state.lockedUntil()).isEqualTo(Instant.parse("2026-10-19T01:15:00Z"));
    }

    @Test
    @DisplayName("only counts while below the limit")
    void countsBelowLimit() {
      assertThat(PinRules.registerFailure(2, 5, 15, now))
          .isEqualTo(new PinRules.FailureState(3, null));
    }

    @Test
    @DisplayName("is locked strictly before lockedUntil")
    void lockedStrictlyBefore() {
      Instant until = Instant.parse("2026-10-19T01:15:00Z");
      assertThat(PinRules.isLocked(until, Instant.parse("2026-10-19T01:14:59Z"))).isTrue();
      assertThat(PinRules.isLocked(until, until)).isFalse();
      assertThat(PinRules.isLocked(null, now)).isFalse();
    }

    @Test
    @DisplayName("accepts exactly six digits")
    void sixDigits() {
      assertThat(PinRules.isWellFormed("123456")).isTrue();
      assertThat(PinRules.isWellFormed("12345")).isFalse();
      assertThat(PinRules.isWellFormed("12a456")).isFalse();
      assertThat(PinRules.isWellFormed(null)).isFalse();
    }
  }

  @Nested
  @DisplayName("Entra email mapping")
  class Entra {
    @Test
    @DisplayName("uses the email claim, lowercased and trimmed")
    void emailClaim() {
      assertThat(EntraEmail.extract("  An.Nguyen@PNJP.test ", null))
          .isEqualTo("an.nguyen@pnjp.test");
    }

    @Test
    @DisplayName("falls back to preferred_username when email is missing or not an address")
    void fallback() {
      assertThat(EntraEmail.extract(null, "Binh@pnjp.test")).isEqualTo("binh@pnjp.test");
      assertThat(EntraEmail.extract("not-an-email", "c@pnjp.test")).isEqualTo("c@pnjp.test");
    }

    @Test
    @DisplayName("returns null when no claim holds an email")
    void none() {
      assertThat(EntraEmail.extract(null, null)).isNull();
      assertThat(EntraEmail.extract(42, null)).isNull();
    }
  }
}
