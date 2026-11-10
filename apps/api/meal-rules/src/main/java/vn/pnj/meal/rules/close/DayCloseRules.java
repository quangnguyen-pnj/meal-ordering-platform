package vn.pnj.meal.rules.close;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.attendance.AttendanceRules.PresenceState;

/**
 * Finalization of day D (BR-43) and what it closes: the admin windows of BR-24, BR-25 and BR-33,
 * attendance after finalization (BR-30, BR-32, plan v1.4) and the adjustment window (BR-46). Every
 * time comes in as an argument and every checkpoint is a setting (plan 7.1 rule 3).
 */
public final class DayCloseRules {
  private DayCloseRules() {}

  private static LocalTime later(LocalTime a, LocalTime b) {
    return a.isAfter(b) ? a : b;
  }

  /**
   * BR-43: the HC admin may press "Finalize" from {@code close.time} (08:30) on day D, and never
   * before the day's lock ({@code day.lockTime}): employees may still change their meals until then
   * (BR-23). Product owner, 2026-11-10: not before 08:30, so late check-ins (BR-32) can still
   * restore meals.
   */
  public static Instant finalizeOpensAt(LocalDate date, LocalTime closeTime, LocalTime lockTime) {
    return VnTime.vnToUtc(date, later(closeTime, lockTime));
  }

  /** Why a finalization is refused, or OK. */
  public enum FinalizeCheck {
    OK,
    ALREADY_FINALIZED,
    TOO_EARLY
  }

  /**
   * BR-43: a day can be finalized once, from {@link #finalizeOpensAt}. A day that was not finalized
   * on day D can still be finalized later (the auto-finalization of {@link #autoFinalizeAt}
   * normally does it first).
   */
  public static FinalizeCheck canFinalize(
      Instant now, LocalDate date, LocalTime closeTime, LocalTime lockTime, boolean finalized) {
    if (finalized) {
      return FinalizeCheck.ALREADY_FINALIZED;
    }
    return now.isBefore(finalizeOpensAt(date, closeTime, lockTime))
        ? FinalizeCheck.TOO_EARLY
        : FinalizeCheck.OK;
  }

  /**
   * Product owner, 2026-11-10: a serving day not finalized by {@code close.autoFinalizeAt} (12:00)
   * is finalized by the system, with no actor. Never before {@link #finalizeOpensAt}, whatever the
   * settings say.
   */
  public static Instant autoFinalizeAt(
      LocalDate date, LocalTime autoFinalizeAt, LocalTime closeTime, LocalTime lockTime) {
    return VnTime.vnToUtc(date, later(autoFinalizeAt, later(closeTime, lockTime)));
  }

  public static boolean isAutoFinalizeDue(Instant now, Instant autoFinalizeAt, boolean finalized) {
    return !finalized && !now.isBefore(autoFinalizeAt);
  }

  /**
   * BR-43: from {@code close.reminderAt} (08:45) on day D, the HC admin is reminded while the day
   * is not finalized.
   */
  public static boolean isReminderDue(
      Instant now, LocalDate date, LocalTime reminderAt, boolean finalized) {
    return !finalized
        && VnTime.vnDateOf(now).equals(date)
        && !now.isBefore(VnTime.vnToUtc(date, reminderAt));
  }

  /**
   * BR-24, BR-25, BR-33: admin changes after the lock run "until finalization". A day is closed for
   * them once finalized, and also once its auto-finalization time has passed: the finalization is
   * then due and is caught up by the next job or request (plan 6.5), so no change can slip in
   * between.
   */
  public static boolean openForAdminChanges(
      Instant now, boolean finalized, Instant autoFinalizeAt) {
    return !finalized && now.isBefore(autoFinalizeAt);
  }

  /**
   * BR-43: at finalization a provisional cancellation becomes a cancellation. Every other state
   * stays: a present or unknown employee keeps their meals (BR-34 held cutoff included).
   */
  public static PresenceState presenceAtFinalization(PresenceState state) {
    return state == PresenceState.PROVISIONAL_ABSENT ? PresenceState.ABSENT : state;
  }

  /**
   * BR-30 / BR-32 (plan v1.4): attendance is read on day D until finalization, then only to record
   * until {@code close.adjustmentDeadline}. No sync runs for the day after that deadline once the
   * day is finalized. Applies to the poll and to the HC admin's re-run alike.
   */
  public static boolean attendanceSyncAllowed(
      Instant now, LocalDate date, boolean finalized, LocalTime adjustmentDeadline) {
    if (!VnTime.vnDateOf(now).equals(date)) {
      return false;
    }
    return !finalized || now.isBefore(VnTime.vnToUtc(date, adjustmentDeadline));
  }

  /** What the HC admin may do about adjustments of day D (BR-46). */
  public enum AdjustmentWindow {
    /** The day is not finalized yet: normal edits still apply. */
    NOT_FINALIZED,
    OPEN,
    /** From {@code close.adjustmentDeadline} on day D. */
    PAST_DEADLINE,
    /** BR-52: after the final delivery confirmation the day's data is locked (P5). */
    DELIVERY_CONFIRMED
  }

  /**
   * BR-46: an adjustment of day D is accepted until {@code close.adjustmentDeadline} (10:30) on day
   * D and before the final delivery confirmation (BR-52).
   */
  public static AdjustmentWindow adjustmentWindow(
      Instant now,
      LocalDate date,
      boolean finalized,
      LocalTime adjustmentDeadline,
      boolean deliveryConfirmed) {
    if (!finalized) {
      return AdjustmentWindow.NOT_FINALIZED;
    }
    if (deliveryConfirmed) {
      return AdjustmentWindow.DELIVERY_CONFIRMED;
    }
    return now.isBefore(VnTime.vnToUtc(date, adjustmentDeadline))
        ? AdjustmentWindow.OPEN
        : AdjustmentWindow.PAST_DEADLINE;
  }

  public static Instant adjustmentDeadlineAt(LocalDate date, LocalTime adjustmentDeadline) {
    return VnTime.vnToUtc(date, adjustmentDeadline);
  }
}
