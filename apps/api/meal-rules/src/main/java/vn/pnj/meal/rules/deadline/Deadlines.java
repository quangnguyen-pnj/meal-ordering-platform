package vn.pnj.meal.rules.deadline;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
   * only; {@link #computeCloseAt} adds the late-publish case (BR-04).
   *
   * @param weekStart Monday of the week being served (week N)
   */
  public static Instant regularCloseAt(LocalDate weekStart, WeekdayTime rule) {
    return VnTime.vnToUtc(dayInPreviousWeek(weekStart, rule), rule.time())
        .plus(Duration.ofMinutes(1));
  }

  /** Week N-1 starts 7 days before weekStart; the rule's weekday counts from Monday. */
  private static LocalDate dayInPreviousWeek(LocalDate weekStart, WeekdayTime rule) {
    requireMonday(weekStart);
    return weekStart.minusDays(7).plusDays(rule.weekday().getValue() - 1L);
  }

  static void requireMonday(LocalDate weekStart) {
    if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
      throw new IllegalArgumentException("weekStart must be a Monday, got " + weekStart);
    }
  }

  /** BR-03: what the UI shows for a closeAt (exclusive 00:00 Friday shows as 23:59 Thursday). */
  public static Instant lastAcceptedMinute(Instant closeAt) {
    return closeAt.minus(Duration.ofMinutes(1));
  }

  /** closeAt is exclusive: registration is open strictly before it. */
  public static boolean isRegistrationOpen(Instant now, Instant closeAt) {
    return now.isBefore(closeAt);
  }

  /**
   * @param latePublish true when BR-04 applied: the menu was published at or after the regular
   *     closeAt
   */
  public record CloseAt(Instant closeAt, boolean latePublish, Instant regularCloseAt) {}

  /**
   * BR-03 / BR-04: closeAt of week N, computed once at publish time.
   *
   * <ul>
   *   <li>Published before the regular closeAt: closeAt is the regular one (a late publish before
   *       that instant does not extend it, BR-03).
   *   <li>Still unpublished at the regular closeAt: closeAt = publish time + {@code
   *       latePublishHours} (BR-04).
   * </ul>
   *
   * Per-day locks still apply on top of this (BR-05, see {@link #dayFreeEditDeadline}).
   */
  public static CloseAt computeCloseAt(
      LocalDate weekStart, Instant publishedAt, WeekdayTime closeRule, int latePublishHours) {
    Instant regular = regularCloseAt(weekStart, closeRule);
    if (publishedAt.isBefore(regular)) {
      return new CloseAt(regular, false, regular);
    }
    return new CloseAt(publishedAt.plus(Duration.ofHours(latePublishHours)), true, regular);
  }

  /** The instant serving day {@code date} locks for employees ({@code day.lockTime}, 08:00). */
  public static Instant dayLockAt(LocalDate date, LocalTime lockTime) {
    return VnTime.vnToUtc(date, lockTime);
  }

  /**
   * BR-05: a late closeAt (BR-04) never pushes a serving day past its own lock. The free-edit
   * deadline of a day is the earlier of the week's closeAt and that day's lock.
   */
  public static Instant dayFreeEditDeadline(LocalDate date, Instant closeAt, LocalTime lockTime) {
    Instant lock = dayLockAt(date, lockTime);
    return lock.isBefore(closeAt) ? lock : closeAt;
  }

  /** BR-06: when the "menu not published" warning for week N starts (Monday 12:00 of N-1). */
  public static Instant menuPublishWarnAt(LocalDate weekStart, WeekdayTime rule) {
    return VnTime.vnToUtc(dayInPreviousWeek(weekStart, rule), rule.time());
  }

  /** BR-06: the week the warning is about: the Monday after the current Vietnam week. */
  public static LocalDate upcomingWeekStart(Instant now) {
    return VnTime.mondayOf(VnTime.vnDateOf(now)).plusDays(7);
  }

  /** BR-06: warn the HC admin from {@code menu.publishWarnAt} until week N's menu is published. */
  public static boolean isMenuPublishWarningDue(
      Instant now, LocalDate weekStart, WeekdayTime warnRule, boolean published) {
    return !published && !now.isBefore(menuPublishWarnAt(weekStart, warnRule));
  }
}
