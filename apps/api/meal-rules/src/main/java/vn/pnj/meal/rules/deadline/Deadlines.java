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
   * When week N's menu was published, relative to its deadlines (BR-03, BR-04).
   *
   * <ul>
   *   <li>{@code ON_TIME}: before the regular closeAt.
   *   <li>{@code LATE}: from the regular closeAt to 00:00 Monday of week N; the HC admin picks the
   *       closeAt (product owner, 2026-11-03).
   *   <li>{@code IN_WEEK}: from 00:00 Monday of week N; there is no forecast, every registration is
   *       an actual one (product owner, 2026-11-03).
   * </ul>
   */
  public enum PublishWindow {
    ON_TIME,
    LATE,
    IN_WEEK
  }

  /** 00:00 Monday of week N in Vietnam time: the latest closeAt of a late publish (BR-04). */
  public static Instant weekStartAt(LocalDate weekStart) {
    requireMonday(weekStart);
    return VnTime.vnToUtc(weekStart, LocalTime.MIDNIGHT);
  }

  public static PublishWindow publishWindow(
      LocalDate weekStart, Instant publishedAt, WeekdayTime closeRule) {
    if (publishedAt.isBefore(regularCloseAt(weekStart, closeRule))) {
      return PublishWindow.ON_TIME;
    }
    return isInWeekPublish(weekStart, publishedAt) ? PublishWindow.IN_WEEK : PublishWindow.LATE;
  }

  /** A menu published from 00:00 Monday of its own week has no forecast (IN_WEEK). */
  public static boolean isInWeekPublish(LocalDate weekStart, Instant publishedAt) {
    return !publishedAt.isBefore(weekStartAt(weekStart));
  }

  /**
   * @param closeAt for {@code LATE}, the suggested closeAt; the HC admin may pick another one
   */
  public record CloseAt(Instant closeAt, PublishWindow window, Instant regularCloseAt) {}

  /**
   * BR-03 / BR-04: closeAt of week N, computed once at publish time.
   *
   * <ul>
   *   <li>{@code ON_TIME}: closeAt is the regular one (a late publish before that instant does not
   *       extend it, BR-03).
   *   <li>{@code LATE}: the HC admin picks closeAt (see {@link #isValidLateCloseAt}); this returns
   *       the suggestion, publish time + {@code latePublishHours}, at most 00:00 Monday of week N.
   *   <li>{@code IN_WEEK}: closeAt is the publish time, so the forecast snapshot is taken at once
   *       and is empty (BR-07).
   * </ul>
   *
   * Per-day locks still apply on top of this (BR-05, see {@link #dayFreeEditDeadline}).
   */
  public static CloseAt computeCloseAt(
      LocalDate weekStart, Instant publishedAt, WeekdayTime closeRule, int latePublishHours) {
    Instant regular = regularCloseAt(weekStart, closeRule);
    PublishWindow window = publishWindow(weekStart, publishedAt, closeRule);
    Instant closeAt =
        switch (window) {
          case ON_TIME -> regular;
          case LATE -> {
            Instant suggested = publishedAt.plus(Duration.ofHours(latePublishHours));
            Instant max = weekStartAt(weekStart);
            yield suggested.isAfter(max) ? max : suggested;
          }
          case IN_WEEK -> publishedAt;
        };
    return new CloseAt(closeAt, window, regular);
  }

  /**
   * BR-04: the closeAt the HC admin picks for a late publish is after the publish time and no later
   * than 00:00 Monday of week N (exclusive, so the last save is Sunday 23:59:59).
   */
  public static boolean isValidLateCloseAt(
      LocalDate weekStart, Instant publishedAt, Instant closeAt) {
    return closeAt.isAfter(publishedAt) && !closeAt.isAfter(weekStartAt(weekStart));
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
