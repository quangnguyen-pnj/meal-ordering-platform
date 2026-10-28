package vn.pnj.meal.rules.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.registration.RegistrationRules.Status;

class RegistrationRulesTest {
  private static final LocalTime LOCK = LocalTime.of(8, 0);

  private static Instant at(String date, String time) {
    return VnTime.vnToUtc(VnTime.parseDate(date), VnTime.parseTimeOfDay(time));
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
