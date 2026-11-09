package vn.pnj.meal.rules.attendance;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.Objects;
import vn.pnj.meal.rules.VnTime;

/**
 * Attendance and cancellation on day D (BR-30 to BR-35). Presence is kept per employee and day;
 * registrations are never touched: meals of a provisionally cancelled employee simply stop counting
 * (plan 6.3, "actual is computed from active registrations and day-D presence").
 */
public final class AttendanceRules {
  private AttendanceRules() {}

  /**
   * Presence of an employee on day D.
   *
   * <ul>
   *   <li>UNKNOWN: nothing in time yet, before the cutoff (or without meals at the cutoff).
   *   <li>PRESENT: checked in by the cutoff, confirmed by a manager in DMP or by an admin.
   *   <li>PROVISIONAL_ABSENT: had meals and was not present at the cutoff (BR-31).
   *   <li>ABSENT: a provisional cancellation made final at finalization (BR-43, P4).
   * </ul>
   */
  public enum PresenceState {
    UNKNOWN,
    PRESENT,
    PROVISIONAL_ABSENT,
    ABSENT
  }

  /** The instant {@code attendance.cutoff} is reached on day D: the provisional-cancel run. */
  public static Instant cutoffAt(LocalDate date, LocalTime cutoff) {
    return VnTime.vnToUtc(date, cutoff);
  }

  /**
   * BR-30: a check-in "no later than 08:10" counts. Like {@code registration.close}, the cutoff is
   * the last accepted minute, so 08:10:59 is still in time and 08:11:00 is not.
   */
  public static boolean checkInInTime(Instant checkInAt, LocalDate date, LocalTime cutoff) {
    return checkInAt != null
        && VnTime.vnDateOf(checkInAt).equals(date)
        && checkInAt.isBefore(cutoffAt(date, cutoff).plus(Duration.ofMinutes(1)));
  }

  /**
   * BR-30 / plan section 5: one person can have several rows; the earliest check-in of day D is
   * used. Check-ins on other days are ignored.
   *
   * @return null when there is no check-in on day D
   */
  public static Instant earliestCheckIn(Collection<Instant> checkIns, LocalDate date) {
    return checkIns.stream()
        .filter(Objects::nonNull)
        .filter(at -> VnTime.vnDateOf(at).equals(date))
        .min(Instant::compareTo)
        .orElse(null);
  }

  /**
   * BR-30 (v1.3): present = a check-in on day D no later than the cutoff, or a manager's presence
   * confirmation for day D in DMP, whatever the arrival time.
   */
  public static boolean isPresent(
      Instant firstCheckInAt, boolean managerConfirmed, LocalDate date, LocalTime cutoff) {
    return managerConfirmed || checkInInTime(firstCheckInAt, date, cutoff);
  }

  /**
   * BR-30 to BR-32: the state after a sync.
   *
   * <ul>
   *   <li>Present per DMP: PRESENT. This is the automatic restore of BR-32: a late record with a
   *       check-in by the cutoff, or a manager's DMP confirmation, brings the meals back.
   *   <li>Otherwise the state stays: a check-in after the cutoff never restores (BR-32), and
   *       presence once established is never taken back by a sync (a record or confirmation that
   *       disappears from DMP keeps the meals, open question Q32).
   *   <li>ABSENT is final: after finalization nothing comes back by itself (BR-46).
   * </ul>
   */
  public static PresenceState afterSync(PresenceState current, boolean presentInDmp) {
    return presentInDmp && current != PresenceState.ABSENT ? PresenceState.PRESENT : current;
  }

  /** BR-31: at the cutoff, an employee with meals who is not present is provisionally cancelled. */
  public static boolean cancelledAtCutoff(PresenceState current, boolean hasMeals) {
    return hasMeals && current == PresenceState.UNKNOWN;
  }

  /** BR-31 / BR-43: meals of a provisionally cancelled or absent employee do not count. */
  public static boolean mealsCount(PresenceState state) {
    return state != PresenceState.PROVISIONAL_ABSENT && state != PresenceState.ABSENT;
  }

  /** What the cutoff run did (BR-31 with the BR-34 safety check). */
  public enum CutoffOutcome {
    /** Employees with meals who were not present became provisionally cancelled. */
    CANCELLED,
    /** BR-34: the latest sync failed (or none succeeded today); all meals are kept. */
    HELD_SYNC_FAILED,
    /** BR-34: fewer than {@code attendance.safetyMinPresentPct} of employees with meals present. */
    HELD_TOO_FEW_PRESENT
  }

  /**
   * BR-34: no bulk cancellation when the latest sync failed, or when fewer than {@code
   * minPresentPct} percent of the employees with meals are present. A day with no meals has nothing
   * to cancel and passes.
   *
   * @param latestSyncSucceeded false also when no sync of day D has succeeded yet
   */
  public static CutoffOutcome safetyCheck(
      boolean latestSyncSucceeded, int withMeals, int presentWithMeals, double minPresentPct) {
    if (!latestSyncSucceeded) {
      return CutoffOutcome.HELD_SYNC_FAILED;
    }
    if (withMeals > 0 && presentWithMeals * 100.0 < minPresentPct * withMeals) {
      return CutoffOutcome.HELD_TOO_FEW_PRESENT;
    }
    return CutoffOutcome.CANCELLED;
  }

  /**
   * BR-30 / plan 6.5: attendance is polled from {@code attendance.pollStart}, every {@code
   * attendance.pollIntervalMin} minutes, until finalization.
   *
   * <p>TODO(BR-43): finalization (DayClose) arrives in P4. Until then polling also ends when day D
   * ends in Vietnam time, like the admin windows (open question Q20).
   *
   * @param lastRunAt start of the latest attendance sync of day D, null if none
   */
  public static boolean isPollDue(
      Instant now,
      LocalDate date,
      LocalTime pollStart,
      int intervalMinutes,
      Instant lastRunAt,
      boolean finalized) {
    if (finalized || !VnTime.vnDateOf(now).equals(date)) {
      return false;
    }
    if (now.isBefore(VnTime.vnToUtc(date, pollStart))) {
      return false;
    }
    return lastRunAt == null || !now.isBefore(lastRunAt.plus(Duration.ofMinutes(intervalMinutes)));
  }

  /** BR-31: the cutoff run is due once the cutoff is reached on day D, and runs once. */
  public static boolean isCutoffDue(
      Instant now, LocalDate date, LocalTime cutoff, boolean alreadyRun, boolean finalized) {
    return !alreadyRun
        && !finalized
        && VnTime.vnDateOf(now).equals(date)
        && !now.isBefore(cutoffAt(date, cutoff));
  }

  /**
   * BR-33: a dept or HC admin confirms presence on day D until finalization. The reason is optional
   * (v1.3).
   *
   * <p>TODO(BR-43): until finalization exists (P4) the window ends with day D in Vietnam time.
   */
  public static boolean canConfirmPresence(Instant now, LocalDate date, boolean finalized) {
    return !finalized && VnTime.vnDateOf(now).equals(date);
  }
}
