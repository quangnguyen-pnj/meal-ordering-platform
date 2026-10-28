package vn.pnj.meal.rules.registration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import vn.pnj.meal.rules.deadline.Deadlines;

/**
 * Registration checkpoints that run without a request: the forecast snapshot (BR-07) and leavers
 * (BR-36). The rest of the registration rules (BR-20 to BR-27) come with registration in J3.
 */
public final class RegistrationRules {
  private RegistrationRules() {}

  public enum Status {
    ACTIVE,
    NEEDS_RESELECT,
    CANCELLED
  }

  /**
   * BR-07 / BR-22: a registration is part of the forecast at closeAt when it is active with a
   * positive quantity. A line waiting for reselection (BR-14) is not a meal anyone chose from the
   * current menu, so it is left out (open-questions Q21).
   */
  public static boolean isForecastLine(Status status, int quantity) {
    return status == Status.ACTIVE && quantity > 0;
  }

  /**
   * BR-36: an employee who leaves loses all meals from their end date. Registrations of a day whose
   * lock has already passed are left to reconciliation, so locked days never change silently.
   */
  public static boolean isLostByLeaver(
      LocalDate date, LocalDate endDate, Instant now, LocalTime lockTime) {
    return !date.isBefore(endDate) && now.isBefore(Deadlines.dayLockAt(date, lockTime));
  }
}
