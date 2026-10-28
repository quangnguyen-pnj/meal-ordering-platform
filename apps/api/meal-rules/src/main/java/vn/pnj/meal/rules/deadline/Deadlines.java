package vn.pnj.meal.rules.deadline;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import vn.pnj.meal.rules.VnTime;

/** Registration deadlines. Every rule reads its time from a setting and takes {@code now} in. */
public final class Deadlines {
  private Deadlines() {}

  /**
   * BR-03: registration for week N closes at the end of Thursday of week N-1.
   *
   * <p>{@code rule} is the {@code registration.close} setting. Its time is the LAST accepted minute
   * (23:59), and the returned closeAt is the exclusive instant one minute later (00:00 Friday), so
   * a save at 23:59:59 is accepted and a save at 00:00:00 is rejected. This is the regular closeAt
   * only; the late-publish case (BR-04) is added with menu publishing.
   *
   * @param weekStart Monday of the week being served (week N)
   */
  public static Instant regularCloseAt(LocalDate weekStart, WeekdayTime rule) {
    if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
      throw new IllegalArgumentException("weekStart must be a Monday, got " + weekStart);
    }
    LocalDate closeDay = weekStart.minusDays(7).plusDays(rule.weekday().getValue() - 1L);
    return VnTime.vnToUtc(closeDay, rule.time()).plus(Duration.ofMinutes(1));
  }

  /** BR-03: what the UI shows for a closeAt (exclusive 00:00 Friday shows as 23:59 Thursday). */
  public static Instant lastAcceptedMinute(Instant closeAt) {
    return closeAt.minus(Duration.ofMinutes(1));
  }

  /** closeAt is exclusive: registration is open strictly before it. */
  public static boolean isRegistrationOpen(Instant now, Instant closeAt) {
    return now.isBefore(closeAt);
  }
}
