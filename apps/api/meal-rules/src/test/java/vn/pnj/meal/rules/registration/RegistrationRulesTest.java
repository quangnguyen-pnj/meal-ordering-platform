package vn.pnj.meal.rules.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.pnj.meal.rules.registration.RegistrationRules.SelectionErrorCode.DUPLICATE_ITEM;
import static vn.pnj.meal.rules.registration.RegistrationRules.SelectionErrorCode.ITEM_NOT_ON_MENU;
import static vn.pnj.meal.rules.registration.RegistrationRules.SelectionErrorCode.QUANTITY_NEGATIVE;
import static vn.pnj.meal.rules.registration.RegistrationRules.SelectionErrorCode.QUANTITY_NOT_INTEGER;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.deadline.Deadlines;
import vn.pnj.meal.rules.deadline.WeekdayTime;
import vn.pnj.meal.rules.registration.RegistrationRules.AdminDayWindow;
import vn.pnj.meal.rules.registration.RegistrationRules.ComparisonLine;
import vn.pnj.meal.rules.registration.RegistrationRules.EmployeeDayWindow;
import vn.pnj.meal.rules.registration.RegistrationRules.Line;
import vn.pnj.meal.rules.registration.RegistrationRules.SelectionError;
import vn.pnj.meal.rules.registration.RegistrationRules.Status;

class RegistrationRulesTest {
  private static final LocalTime LOCK = LocalTime.of(8, 0);
  private static final WeekdayTime CLOSE_RULE =
      new WeekdayTime(java.time.DayOfWeek.THURSDAY, LocalTime.of(23, 59));

  // Week N starts Monday 2026-11-02; closeAt is Friday 2026-10-30 00:00 (shown 23:59 Thursday).
  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
  private static final Instant CLOSE_AT = Deadlines.regularCloseAt(MONDAY, CLOSE_RULE);

  private static Instant at(String date, String time) {
    return VnTime.vnToUtc(VnTime.parseDate(date), VnTime.parseTimeOfDay(time));
  }

  private static Instant at(String date, String time, int seconds) {
    return at(date, time).plus(Duration.ofSeconds(seconds));
  }

  @Nested
  @DisplayName("BR-20: no portion limit (example table of plan 2.3)")
  class Selection {
    // Menu of Mặn Cá kho, Mặn Thịt kho, Chay, Cháo.
    private final Set<String> menu = Set.of("ca", "thit", "chay", "chao");

    @Test
    @DisplayName("BR-20: 3 Mặn Cá kho is valid (no portion limit)")
    void three() {
      assertThat(RegistrationRules.validateDaySelection(List.of(new Line("ca", 3)), menu))
          .isEmpty();
    }

    @Test
    @DisplayName("BR-20: 1 Chay + 1 Cháo + 1 Mặn Cá kho + 1 Mặn Thịt kho is valid")
    void severalTypes() {
      List<Line> lines =
          List.of(new Line("chay", 1), new Line("chao", 1), new Line("ca", 1), new Line("thit", 1));
      assertThat(RegistrationRules.validateDaySelection(lines, menu)).isEmpty();
    }

    @Test
    @DisplayName("BR-20: 0 of every dish is valid and means not registered")
    void zero() {
      List<Line> lines = menu.stream().map(id -> new Line(id, 0)).toList();
      assertThat(RegistrationRules.validateDaySelection(lines, menu)).isEmpty();
      assertThat(RegistrationRules.validateDaySelection(List.of(), menu)).isEmpty();
      assertThat(RegistrationRules.quantitiesOf(lines)).isEmpty();
    }

    @Test
    @DisplayName("BR-20: −1 is invalid, 1.5 is invalid, a dish off the menu is invalid")
    void invalid() {
      assertThat(RegistrationRules.validateDaySelection(List.of(new Line("ca", -1)), menu))
          .containsExactly(new SelectionError(QUANTITY_NEGATIVE, "ca"));
      assertThat(RegistrationRules.validateDaySelection(List.of(new Line("chay", 1.5)), menu))
          .containsExactly(new SelectionError(QUANTITY_NOT_INTEGER, "chay"));
      assertThat(RegistrationRules.validateDaySelection(List.of(new Line("pho", 1)), menu))
          .containsExactly(new SelectionError(ITEM_NOT_ON_MENU, "pho"));
    }

    @Test
    @DisplayName("BR-20: the same dish twice in one selection is rejected as ambiguous")
    void duplicate() {
      assertThat(
              RegistrationRules.validateDaySelection(
                  List.of(new Line("ca", 1), new Line("ca", 2)), menu))
          .containsExactly(new SelectionError(DUPLICATE_ITEM, "ca"));
    }

    @Test
    @DisplayName("BR-20: a cap could later be added as a setting; none applies today")
    void cap() {
      List<Line> lines = List.of(new Line("ca", 50));
      assertThat(RegistrationRules.validateDaySelection(lines, menu)).isEmpty();
      assertThat(RegistrationRules.validateDaySelection(lines, menu, 10)).hasSize(1);
    }
  }

  @Nested
  @DisplayName("BR-22 to BR-24: what an employee may change, and when")
  class EmployeeWindow {
    private EmployeeDayWindow window(Instant now, LocalDate date, boolean forecastTaken) {
      return RegistrationRules.employeeDayWindow(now, date, CLOSE_AT, LOCK, forecastTaken);
    }

    private EmployeeDayWindow window(Instant now) {
      return window(now, MONDAY, false);
    }

    @Test
    @DisplayName("BR-22: before closeAt an employee adds, changes and removes freely")
    void open() {
      assertThat(window(at("2026-10-27", "10:00"))).isEqualTo(EmployeeDayWindow.OPEN);
      assertThat(window(at("2026-10-29", "23:59", 59))).isEqualTo(EmployeeDayWindow.OPEN);
    }

    @Test
    @DisplayName("BR-22 / BR-23: from closeAt to 08:00 on day D, changes are still allowed")
    void afterClose() {
      assertThat(window(at("2026-10-30", "00:00"))).isEqualTo(EmployeeDayWindow.AFTER_CLOSE);
      assertThat(window(at("2026-10-31", "15:00"))).isEqualTo(EmployeeDayWindow.AFTER_CLOSE);
      assertThat(window(at("2026-11-02", "07:59", 59))).isEqualTo(EmployeeDayWindow.AFTER_CLOSE);
    }

    @Test
    @DisplayName("BR-23: once the forecast was taken, a change counts as after closeAt")
    void forecastTaken() {
      assertThat(window(at("2026-10-29", "23:59", 59), MONDAY, true))
          .isEqualTo(EmployeeDayWindow.AFTER_CLOSE);
    }

    @Test
    @DisplayName("BR-24: at 08:00 on day D employees cannot change anything")
    void locked() {
      assertThat(window(at("2026-11-02", "08:00"))).isEqualTo(EmployeeDayWindow.LOCKED);
      assertThat(window(at("2026-11-02", "12:00"))).isEqualTo(EmployeeDayWindow.LOCKED);
      assertThat(window(at("2026-11-02", "08:00"), MONDAY.plusDays(1), false))
          .isEqualTo(EmployeeDayWindow.AFTER_CLOSE);
    }

    @Test
    @DisplayName("BR-05 / BR-24: a late closeAt after a day's 08:00 still locks that day at 08:00")
    void lateClose() {
      Instant lateClose = at("2026-11-02", "20:00");
      Instant now = at("2026-11-02", "09:00");
      assertThat(RegistrationRules.employeeDayWindow(now, MONDAY, lateClose, LOCK, false))
          .isEqualTo(EmployeeDayWindow.LOCKED);
      assertThat(
              RegistrationRules.employeeDayWindow(now, MONDAY.plusDays(1), lateClose, LOCK, false))
          .isEqualTo(EmployeeDayWindow.OPEN);
    }

    @Test
    @DisplayName("BR-24: the lock time comes from the setting")
    void lockSetting() {
      assertThat(
              RegistrationRules.employeeDayWindow(
                  at("2026-11-02", "08:30"), MONDAY, CLOSE_AT, LocalTime.of(9, 0), false))
          .isEqualTo(EmployeeDayWindow.AFTER_CLOSE);
    }
  }

  @Nested
  @DisplayName("BR-21 / BR-25: what an admin may do for an employee")
  class AdminWindow {
    private AdminDayWindow window(Instant now, boolean finalized) {
      return RegistrationRules.adminDayWindow(now, MONDAY, LOCK, finalized);
    }

    @Test
    @DisplayName("BR-21: dept admins register on behalf until 08:00 on day D, also after closeAt")
    void onBehalf() {
      assertThat(window(at("2026-10-27", "09:00"), false)).isEqualTo(AdminDayWindow.ON_BEHALF);
      assertThat(window(at("2026-11-02", "07:59"), false)).isEqualTo(AdminDayWindow.ON_BEHALF);
    }

    @Test
    @DisplayName("BR-25: after 08:00 and before finalization, admins only add Extra meals")
    void extra() {
      assertThat(window(at("2026-11-02", "08:00"), false)).isEqualTo(AdminDayWindow.EXTRA);
      assertThat(window(at("2026-11-02", "23:59"), false)).isEqualTo(AdminDayWindow.EXTRA);
    }

    @Test
    @DisplayName("BR-25: after finalization, or once day D ends, nothing goes through registration")
    void closed() {
      assertThat(window(at("2026-11-02", "08:40"), true)).isEqualTo(AdminDayWindow.CLOSED);
      assertThat(window(at("2026-11-03", "00:00"), false)).isEqualTo(AdminDayWindow.CLOSED);
    }
  }

  @Test
  @DisplayName("BR-07: the forecast holds active lines with a positive quantity only")
  void forecastLines() {
    assertThat(RegistrationRules.isForecastLine(Status.ACTIVE, 2)).isTrue();
    assertThat(RegistrationRules.isForecastLine(Status.ACTIVE, 0)).isFalse();
    assertThat(RegistrationRules.isForecastLine(Status.NEEDS_RESELECT, 1)).isFalse();
    assertThat(RegistrationRules.isForecastLine(Status.CANCELLED, 1)).isFalse();
  }

  @Test
  @DisplayName("BR-23: an added meal and a first meal without forecast both show as differences")
  void differences() {
    var added = RegistrationRules.compareWithForecast(Map.of("ca", 1), map("ca", 1, "chay", 1));
    assertThat(added.changed()).isTrue();
    assertThat(added.lines())
        .containsExactly(new ComparisonLine("ca", 1, 1, 0), new ComparisonLine("chay", 0, 1, 1));
    assertThat(List.of(added.forecastTotal(), added.actualTotal())).containsExactly(1, 2);

    var noForecast = RegistrationRules.compareWithForecast(Map.of(), Map.of("ca", 1));
    assertThat(noForecast.changed()).isTrue();
    assertThat(noForecast.forecastTotal()).isZero();
    assertThat(noForecast.actualTotal()).isEqualTo(1);
  }

  @Test
  @DisplayName("BR-23: removing everything is a negative difference; unchanged is no difference")
  void removed() {
    assertThat(RegistrationRules.compareWithForecast(Map.of("ca", 2), Map.of()).lines())
        .containsExactly(new ComparisonLine("ca", 2, 0, -2));
    assertThat(
            RegistrationRules.compareWithForecast(Map.of("ca", 2), map("ca", 2, "chay", 0))
                .changed())
        .isFalse();
  }

  private static Map<String, Integer> map(String k1, int v1, String k2, int v2) {
    Map<String, Integer> m = new java.util.LinkedHashMap<>();
    m.put(k1, v1);
    m.put(k2, v2);
    return m;
  }

  @Test
  @DisplayName("BR-36: meals on and after the end date are lost; earlier ones are kept")
  void leaver() {
    Instant now = at("2026-11-02", "02:00");
    LocalDate end = LocalDate.of(2026, 11, 4);
    assertThat(RegistrationRules.isLostByLeaver(LocalDate.of(2026, 11, 3), end, now, LOCK))
        .isFalse();
    assertThat(RegistrationRules.isLostByLeaver(end, end, now, LOCK)).isTrue();
    assertThat(RegistrationRules.isLostByLeaver(LocalDate.of(2026, 11, 6), end, now, LOCK))
        .isTrue();
  }

  @Test
  @DisplayName("BR-36: a day whose lock has passed is left alone (reconciliation shows it)")
  void lockedDay() {
    assertThat(
            RegistrationRules.isLostByLeaver(
                LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 10, 30),
                at("2026-11-02", "09:00"),
                LOCK))
        .isFalse();
  }
}
