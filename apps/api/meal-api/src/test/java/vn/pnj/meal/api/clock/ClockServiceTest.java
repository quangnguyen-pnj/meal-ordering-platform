package vn.pnj.meal.api.clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.rules.deadline.Deadlines;
import vn.pnj.meal.rules.deadline.WeekdayTime;

class ClockServiceTest {
  private static final Instant REAL = Instant.parse("2026-10-28T01:00:00Z");
  private final Clock fixedSystem = Clock.fixed(REAL, ZoneOffset.UTC);

  @Test
  void returnsRealTimeWhenFakeTimeIsNotSet() {
    ClockService clock = new ClockService(fixedSystem, true);
    assertThat(clock.now()).isEqualTo(REAL);
    assertThat(clock.isFake()).isFalse();
  }

  @Test
  void freezesAtTheFakeInstantUntilAdvancedOrCleared() {
    ClockService clock = new ClockService(fixedSystem, true);
    clock.setFakeTime(Instant.parse("2026-10-22T16:59:30Z"));
    assertThat(clock.now()).isEqualTo(Instant.parse("2026-10-22T16:59:30Z"));
    clock.advanceFakeTime(Duration.ofSeconds(30));
    assertThat(clock.now()).isEqualTo(Instant.parse("2026-10-22T17:00:00Z"));
    clock.clearFakeTime();
    assertThat(clock.isFake()).isFalse();
    assertThat(clock.now()).isEqualTo(REAL);
  }

  @Test
  void refusesFakeTimeUnlessAllowed() {
    ClockService clock = new ClockService(fixedSystem, false);
    assertThat(clock.fakeTimeAllowed()).isFalse();
    assertThatThrownBy(() -> clock.setFakeTime(REAL))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Fake time is disabled");
  }

  @Test
  @DisplayName(
      "BR-03: fake time at Thursday 23:59:30 and at Friday 00:00 gives different registration"
          + " results")
  void br03() {
    ClockService clock = new ClockService(fixedSystem, true);
    Instant closeAt =
        Deadlines.regularCloseAt(
            LocalDate.of(2026, 10, 26), new WeekdayTime(DayOfWeek.THURSDAY, LocalTime.of(23, 59)));

    clock.setFakeTime(OffsetDateTime.parse("2026-10-22T23:59:30+07:00").toInstant());
    boolean thursdayNight = Deadlines.isRegistrationOpen(clock.now(), closeAt);
    clock.setFakeTime(OffsetDateTime.parse("2026-10-23T00:00:00+07:00").toInstant());
    boolean fridayMidnight = Deadlines.isRegistrationOpen(clock.now(), closeAt);

    assertThat(thursdayNight).isTrue();
    assertThat(fridayMidnight).isFalse();
  }

  @Test
  @DisplayName("keeps milliseconds only, like the TIMESTAMP(3) columns")
  void millis() {
    ClockService clock =
        new ClockService(
            Clock.fixed(Instant.parse("2026-10-28T01:00:00.123456789Z"), ZoneOffset.UTC), true);
    assertThat(clock.now()).isEqualTo(Instant.parse("2026-10-28T01:00:00.123Z"));
  }
}
