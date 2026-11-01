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
 * (and, from P4, on reconciliation).
 */
public final class DayCases {
  private DayCases() {}

  public enum CaseType {
    /** BR-31: had meals and was not present at the cutoff. */
    PROVISIONALLY_CANCELLED,
    /** BR-43: a provisional cancellation made final (P4). */
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
    /** BR-35: a leave flag while the employee still has meals. */
    ON_LEAVE_WITH_MEALS
  }

  /**
   * @param forecast the forecast of day D by menu item; null while the forecast is not taken (then
   *     nothing is a difference yet, BR-07)
   * @param actual active quantities by menu item (before presence is applied)
   * @param checkedInInTime BR-30: a check-in no later than the cutoff
   */
  public record Input(
      Map<String, Integer> forecast,
      Map<String, Integer> actual,
      PresenceState presence,
      boolean checkedInInTime,
      boolean confirmedInDmp,
      boolean confirmedByAdmin,
      boolean onLeave,
      boolean hasExtra) {}

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
    if (in.confirmedInDmp() && !in.checkedInInTime()) {
      cases.add(CaseType.PRESENCE_CONFIRMED_IN_DMP);
    }
    // TODO(BR-41): every leave flag counts, afternoon leave included (open question Q28).
    if (in.onLeave() && hasMeals) {
      cases.add(CaseType.ON_LEAVE_WITH_MEALS);
    }
    return List.copyOf(cases);
  }
}
