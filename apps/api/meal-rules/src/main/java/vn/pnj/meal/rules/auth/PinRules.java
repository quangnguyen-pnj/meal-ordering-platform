package vn.pnj.meal.rules.auth;

import java.time.Duration;
import java.time.Instant;

/** Employee code + PIN sign-in: the temporary lock after repeated wrong PINs (pin.* settings). */
public final class PinRules {
  private PinRules() {}

  /** True while the account is temporarily locked by repeated wrong PINs. */
  public static boolean isLocked(Instant lockedUntil, Instant now) {
    return lockedUntil != null && now.isBefore(lockedUntil);
  }

  /** Counter and lock after a wrong PIN. */
  public record FailureState(int failedCount, Instant lockedUntil) {}

  /**
   * State after one more wrong PIN. Reaching {@code maxAttempts} locks the account for {@code
   * lockMinutes} and restarts the counter, so the next window starts clean once the lock expires.
   */
  public static FailureState registerFailure(
      int failedCount, int maxAttempts, int lockMinutes, Instant now) {
    int next = failedCount + 1;
    if (next >= maxAttempts) {
      return new FailureState(0, now.plus(Duration.ofMinutes(lockMinutes)));
    }
    return new FailureState(next, null);
  }

  /** A PIN is exactly six digits. */
  public static boolean isWellFormed(String pin) {
    return pin != null && pin.matches("\\d{6}");
  }
}
