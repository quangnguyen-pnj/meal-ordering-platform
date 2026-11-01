package vn.pnj.meal.rules.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.pnj.meal.rules.attendance.AttendanceRules.PresenceState.PRESENT;
import static vn.pnj.meal.rules.attendance.AttendanceRules.PresenceState.PROVISIONAL_ABSENT;
import static vn.pnj.meal.rules.attendance.AttendanceRules.PresenceState.UNKNOWN;
import static vn.pnj.meal.rules.attendance.DayCases.CaseType.ADDED;
import static vn.pnj.meal.rules.attendance.DayCases.CaseType.DISH_CHANGED;
import static vn.pnj.meal.rules.attendance.DayCases.CaseType.EXTRA;
import static vn.pnj.meal.rules.attendance.DayCases.CaseType.NOT_EATING;
import static vn.pnj.meal.rules.attendance.DayCases.CaseType.ON_LEAVE_WITH_MEALS;
import static vn.pnj.meal.rules.attendance.DayCases.CaseType.PRESENCE_CONFIRMED_BY_ADMIN;
import static vn.pnj.meal.rules.attendance.DayCases.CaseType.PRESENCE_CONFIRMED_IN_DMP;
import static vn.pnj.meal.rules.attendance.DayCases.CaseType.PROVISIONALLY_CANCELLED;
import static vn.pnj.meal.rules.attendance.DayCases.CaseType.REDUCED;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.rules.attendance.AttendanceRules.PresenceState;
import vn.pnj.meal.rules.attendance.DayCases.Input;
import vn.pnj.meal.rules.attendance.DayCases.LeaveDayType;

class DayCasesTest {
  private static Input in(
      Map<String, Integer> forecast, Map<String, Integer> actual, PresenceState presence) {
    return new Input(forecast, actual, presence, true, false, false, null, false);
  }

  @Test
  @DisplayName("BR-41: an employee whose meals match the forecast and who checked in is no case")
  void none() {
    assertThat(DayCases.casesFor(in(Map.of("a", 1), Map.of("a", 1), PRESENT))).isEmpty();
  }

  @Test
  @DisplayName("BR-41: provisionally cancelled is a case")
  void provisional() {
    assertThat(DayCases.casesFor(in(Map.of("a", 1), Map.of("a", 1), PROVISIONAL_ABSENT)))
        .containsExactly(PROVISIONALLY_CANCELLED);
  }

  @Test
  @DisplayName("BR-41: not eating, reduced and added after closeAt (also without forecast)")
  void differences() {
    assertThat(DayCases.casesFor(in(Map.of("a", 1), Map.of(), UNKNOWN)))
        .containsExactly(NOT_EATING);
    assertThat(DayCases.casesFor(in(Map.of("a", 2), Map.of("a", 1), UNKNOWN)))
        .containsExactly(REDUCED);
    assertThat(DayCases.casesFor(in(Map.of(), Map.of("a", 1), UNKNOWN))).containsExactly(ADDED);
  }

  @Test
  @DisplayName("BR-41: a dish changed from the forecast is a case")
  void dishChanged() {
    assertThat(DayCases.casesFor(in(Map.of("a", 1), Map.of("b", 1), PRESENT)))
        .containsExactly(DISH_CHANGED);
  }

  @Test
  @DisplayName("BR-41: no difference cases before the forecast is taken")
  void noForecastYet() {
    assertThat(DayCases.casesFor(in(null, Map.of("a", 3), UNKNOWN))).isEmpty();
  }

  @Test
  @DisplayName("BR-41: extra, admin confirmation, DMP confirmation and leave with meals")
  void flags() {
    Input input =
        new Input(
            Map.of("a", 1), Map.of("a", 1), PRESENT, false, true, true, LeaveDayType.AM, true);
    assertThat(DayCases.casesFor(input))
        .containsExactly(
            EXTRA, PRESENCE_CONFIRMED_BY_ADMIN, PRESENCE_CONFIRMED_IN_DMP, ON_LEAVE_WITH_MEALS);
  }

  @Test
  @DisplayName("BR-41: a DMP confirmation is no case when the employee also checked in in time")
  void confirmedAndCheckedIn() {
    Input input =
        new Input(Map.of("a", 1), Map.of("a", 1), PRESENT, true, true, false, null, false);
    assertThat(DayCases.casesFor(input)).isEmpty();
  }

  @Test
  @DisplayName(
      "BR-41 / Q28: full-day and morning leave with lunch are cases; afternoon leave is not")
  void leave() {
    for (LeaveDayType leave : LeaveDayType.values()) {
      Input input =
          new Input(Map.of("a", 1), Map.of("a", 1), PRESENT, true, false, false, leave, false);
      if (leave == LeaveDayType.PM) {
        assertThat(DayCases.casesFor(input)).isEmpty();
      } else {
        assertThat(DayCases.casesFor(input)).containsExactly(ON_LEAVE_WITH_MEALS);
      }
    }
  }
}
