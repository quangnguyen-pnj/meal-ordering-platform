package vn.pnj.meal.rules.attendance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import vn.pnj.meal.rules.attendance.AttendanceRules.PresenceState;
import vn.pnj.meal.rules.registration.RegistrationRules;
import vn.pnj.meal.rules.registration.RegistrationRules.Comparison;
import vn.pnj.meal.rules.registration.RegistrationRules.ComparisonLine;

/**
 * BR-41: the cases to review for one employee on day D, shown on the exceptions tab of /admin/today
 * and on reconciliation.
 */
public final class DayCases {
  private DayCases() {}

  public enum CaseType {
    /** BR-31: had meals and was not present at the cutoff. */
    PROVISIONALLY_CANCELLED,
    /** BR-43: a provisional cancellation made final at finalization. */
    CANCELLED_ABSENT,
    /** Had a forecast and has no meal now. */
    NOT_EATING,
    /** Fewer meals than the forecast, but not none. */
    REDUCED,
    /** More meals than the forecast, including employees without a forecast (BR-23). */
    ADDED,
    /** One meal of the forecast swapped for another. */
    DISH_CHANGED,
    /** BR-25: meals added by an admin after the lock. */
    EXTRA,
    /** BR-33: an admin confirmed presence. */
    PRESENCE_CONFIRMED_BY_ADMIN,
    /** BR-32: a manager confirmed presence in DMP, without a check-in in time. */
    PRESENCE_CONFIRMED_IN_DMP,
    /** BR-35: a full-day or morning leave while the employee still has lunch (Q33). */
    ON_LEAVE_WITH_MEALS,
    /**
     * BR-32 / BR-41 (v1.4): a manager's DMP confirmation that arrived after finalization. It adds
     * nothing; the HC admin decides on an adjustment (BR-46).
     */
    LATE_DMP_CONFIRMATION,
    /**
     * BR-32 (v1.4): a check-in of a cancelled employee that DMP returned only after finalization.
     * Recorded only; the HC admin decides on an adjustment (BR-46).
     */
    LATE_CHECK_IN
  }

  /**
   * @param forecast the forecast of day D by menu item; null while the forecast is not taken (then
   *     nothing is a difference yet, BR-07)
   * @param actual active quantities by menu item (before presence is applied)
   * @param checkedInInTime BR-30: a check-in no later than the cutoff
   * @param leave the day's leave flag, null when none
   * @param lateDmpConfirmation the DMP confirmation was first seen after finalization
   * @param lateCheckIn a check-in was first seen after finalization for a cancelled employee
   */
  public record Input(
      Map<String, Integer> forecast,
      Map<String, Integer> actual,
      PresenceState presence,
      boolean checkedInInTime,
      boolean confirmedInDmp,
      boolean confirmedByAdmin,
      LeaveDayType leave,
      boolean hasExtra,
      boolean lateDmpConfirmation,
      boolean lateCheckIn) {

    /** Before finalization nothing can be late. */
    public Input(
        Map<String, Integer> forecast,
        Map<String, Integer> actual,
        PresenceState presence,
        boolean checkedInInTime,
        boolean confirmedInDmp,
        boolean confirmedByAdmin,
        LeaveDayType leave,
        boolean hasExtra) {
      this(
          forecast,
          actual,
          presence,
          checkedInInTime,
          confirmedInDmp,
          confirmedByAdmin,
          leave,
          hasExtra,
          false,
          false);
    }
  }

  /** A leave flag as DMP gives it: full day, morning or afternoon (BR-35). */
  public enum LeaveDayType {
    FULL,
    AM,
    PM
  }

  /**
   * BR-41 / Q33 (product owner, 2026-11-01): only full-day and morning leave make "on leave but
   * still has meals" a case. Afternoon leave keeps lunch (BR-35), so it is not one.
   */
  public static boolean leavesOutLunch(LeaveDayType leave) {
    return leave == LeaveDayType.FULL || leave == LeaveDayType.AM;
  }

  public static List<CaseType> casesFor(Input in) {
    List<CaseType> cases = new ArrayList<>();
    boolean hasMeals = in.actual().values().stream().anyMatch(q -> q > 0);
    if (hasMeals && in.presence() == PresenceState.PROVISIONAL_ABSENT) {
      cases.add(CaseType.PROVISIONALLY_CANCELLED);
    }
    if (hasMeals && in.presence() == PresenceState.ABSENT) {
      cases.add(CaseType.CANCELLED_ABSENT);
    }
    if (in.forecast() != null) {
      Comparison c = RegistrationRules.compareWithForecast(in.forecast(), in.actual());
      if (c.forecastTotal() > 0 && c.actualTotal() == 0) {
        cases.add(CaseType.NOT_EATING);
      } else if (c.actualTotal() < c.forecastTotal()) {
        cases.add(CaseType.REDUCED);
      } else if (c.actualTotal() > c.forecastTotal()) {
        cases.add(CaseType.ADDED);
      }
      boolean up = c.lines().stream().mapToInt(ComparisonLine::difference).anyMatch(d -> d > 0);
      boolean down = c.lines().stream().mapToInt(ComparisonLine::difference).anyMatch(d -> d < 0);
      if (up && down) {
        cases.add(CaseType.DISH_CHANGED);
      }
    }
    if (in.hasExtra() && hasMeals) {
      cases.add(CaseType.EXTRA);
    }
    if (in.confirmedByAdmin()) {
      cases.add(CaseType.PRESENCE_CONFIRMED_BY_ADMIN);
    }
    if (in.lateDmpConfirmation()) {
      cases.add(CaseType.LATE_DMP_CONFIRMATION);
    } else if (in.confirmedInDmp() && !in.checkedInInTime()) {
      cases.add(CaseType.PRESENCE_CONFIRMED_IN_DMP);
    }
    if (in.lateCheckIn() && !in.lateDmpConfirmation()) {
      cases.add(CaseType.LATE_CHECK_IN);
    }
    if (leavesOutLunch(in.leave()) && hasMeals) {
      cases.add(CaseType.ON_LEAVE_WITH_MEALS);
    }
    return List.copyOf(cases);
  }
}
