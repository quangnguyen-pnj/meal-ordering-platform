package vn.pnj.meal.rules.deadline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.rules.VnTime;

@DisplayName("BR-03: regular registration closeAt")
class DeadlinesTest {
  /** The default of the registration.close setting. */
  static final WeekdayTime CLOSE_RULE = new WeekdayTime(DayOfWeek.THURSDAY, LocalTime.of(23, 59));

  // Week N starts Monday 2026-10-26, so week N-1's Thursday is 2026-10-22.
  private static final LocalDate WEEK_START = LocalDate.of(2026, 10, 26);

  @Test
  @DisplayName(
      "BR-03: closeAt is 00:00 Friday Vietnam time, one minute after the displayed 23:59 Thursday")
  void closeAtFridayMidnight() {
    Instant closeAt = Deadlines.regularCloseAt(WEEK_START, CLOSE_RULE);
    LocalDateTime vn = VnTime.toVn(closeAt);
    assertThat(vn).isEqualTo(LocalDateTime.of(2026, 10, 23, 0, 0, 0));
    assertThat(vn.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
    assertThat(closeAt).isEqualTo(Instant.parse("2026-10-22T17:00:00Z"));
  }

  @Test
  @DisplayName("BR-03: the UI shows 23:59 Thursday for a closeAt of 00:00 Friday")
  void shownAs2359() {
    LocalDateTime shown =
        VnTime.toVn(Deadlines.lastAcceptedMinute(Deadlines.regularCloseAt(WEEK_START, CLOSE_RULE)));
    assertThat(shown).isEqualTo(LocalDateTime.of(2026, 10, 22, 23, 59));
    assertThat(shown.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
  }

  @Test
  @DisplayName("BR-03: a save at Thursday 23:59:30 is accepted")
  void accepted2359_30() {
    Instant closeAt = Deadlines.regularCloseAt(WEEK_START, CLOSE_RULE);
    assertThat(Deadlines.isRegistrationOpen(Instant.parse("2026-10-22T16:59:30Z"), closeAt))
        .isTrue();
  }

  @Test
  @DisplayName("BR-03: a save at Thursday 23:59:59 is accepted")
  void accepted2359_59() {
    Instant closeAt = Deadlines.regularCloseAt(WEEK_START, CLOSE_RULE);
    assertThat(Deadlines.isRegistrationOpen(Instant.parse("2026-10-22T16:59:59Z"), closeAt))
        .isTrue();
  }

  @Test
  @DisplayName("BR-03: closeAt is exclusive, so a save at Friday 00:00:00 is rejected")
  void rejectedAtMidnight() {
    Instant closeAt = Deadlines.regularCloseAt(WEEK_START, CLOSE_RULE);
    assertThat(Deadlines.isRegistrationOpen(Instant.parse("2026-10-22T17:00:00Z"), closeAt))
        .isFalse();
  }

  @Test
  @DisplayName("BR-03: the close weekday and time come from the setting, not from code")
  void fromSetting() {
    Instant closeAt =
        Deadlines.regularCloseAt(
            WEEK_START, new WeekdayTime(DayOfWeek.WEDNESDAY, LocalTime.of(17, 0)));
    assertThat(closeAt).isEqualTo(Instant.parse("2026-10-21T10:01:00Z"));
  }

  @Test
  @DisplayName("BR-03: weekStart must be a Monday")
  void mondayOnly() {
    assertThatThrownBy(() -> Deadlines.regularCloseAt(LocalDate.of(2026, 10, 27), CLOSE_RULE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("BR-03: closeAt crosses a month and year boundary correctly")
  void yearBoundary() {
    Instant closeAt = Deadlines.regularCloseAt(LocalDate.of(2027, 1, 4), CLOSE_RULE);
    assertThat(VnTime.vnDateOf(closeAt)).isEqualTo(LocalDate.of(2027, 1, 1));
  }
}
