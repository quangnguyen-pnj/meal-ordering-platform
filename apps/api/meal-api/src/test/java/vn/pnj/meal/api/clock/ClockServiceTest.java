package vn.pnj.meal.api.clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.api.common.ApiException;

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

  // The BR-03 check (Thursday 23:59:30 vs Friday 00:00) moves here with regularCloseAt in J1.
}
