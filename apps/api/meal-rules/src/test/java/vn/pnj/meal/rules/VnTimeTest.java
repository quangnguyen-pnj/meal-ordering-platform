package vn.pnj.meal.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("time helpers (Asia/Ho_Chi_Minh, UTC+7)")
class VnTimeTest {

  @Test
  @DisplayName("converts a UTC instant to Vietnam wall time across midnight")
  void toVnAcrossMidnight() {
    LocalDateTime vn = VnTime.toVn(Instant.parse("2026-10-22T17:00:30Z"));
    assertThat(vn).isEqualTo(LocalDateTime.of(2026, 10, 23, 0, 0, 30));
    assertThat(vn.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
  }

  @Test
  @DisplayName("round-trips vnToUtc and toVn")
  void roundTrip() {
    Instant utc = VnTime.vnToUtc(LocalDate.of(2026, 10, 22), LocalTime.of(23, 59));
    assertThat(utc).isEqualTo(Instant.parse("2026-10-22T16:59:00Z"));
    assertThat(VnTime.toVn(utc)).isEqualTo(LocalDateTime.of(2026, 10, 22, 23, 59));
    assertThat(VnTime.vnToUtc(LocalDate.of(2026, 10, 19), LocalTime.of(8, 0)))
        .isEqualTo(Instant.parse("2026-10-19T01:00:00Z"));
    assertThat(VnTime.vnDateOf(Instant.parse("2026-10-22T16:59:59Z")))
        .isEqualTo(LocalDate.of(2026, 10, 22));
    assertThat(VnTime.vnDateOf(Instant.parse("2026-10-22T17:00:00Z")))
        .isEqualTo(LocalDate.of(2026, 10, 23));
  }

  @Test
  @DisplayName("finds the Monday of a week")
  void mondayOf() {
    assertThat(VnTime.mondayOf(LocalDate.of(2026, 10, 25))).isEqualTo(LocalDate.of(2026, 10, 19));
    assertThat(VnTime.mondayOf(LocalDate.of(2026, 10, 19))).isEqualTo(LocalDate.of(2026, 10, 19));
    assertThat(VnTime.mondayOf(LocalDate.of(2027, 1, 1))).isEqualTo(LocalDate.of(2026, 12, 28));
  }

  @Test
  @DisplayName("parses dates and times strictly")
  void parses() {
    assertThat(VnTime.parseDate("2028-02-29")).isEqualTo(LocalDate.of(2028, 2, 29));
    assertThat(VnTime.parseTimeOfDay("08:00")).isEqualTo(LocalTime.of(8, 0));
    assertThat(VnTime.parseTimeOfDay("23:59")).isEqualTo(LocalTime.of(23, 59));
  }

  @Test
  @DisplayName("rejects malformed or impossible dates and times")
  void rejects() {
    for (String bad : new String[] {"2026-02-30", "2027-02-29", "2026-1-01", "26-01-01", ""}) {
      assertThatThrownBy(() -> VnTime.parseDate(bad)).isInstanceOf(IllegalArgumentException.class);
    }
    for (String bad : new String[] {"24:00", "8:00", "08:60", "08:00:00", ""}) {
      assertThatThrownBy(() -> VnTime.parseTimeOfDay(bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @DisplayName("ISO week: counts weeks from the week with the first Thursday")
  void isoWeek() {
    assertThat(VnTime.isoWeekOf(LocalDate.of(2026, 10, 19)))
        .isEqualTo(new VnTime.IsoWeek(2026, 43));
    assertThat(VnTime.isoWeekOf(LocalDate.of(2026, 10, 23)))
        .isEqualTo(new VnTime.IsoWeek(2026, 43));
    assertThat(VnTime.isoWeekOf(LocalDate.of(2026, 7, 6))).isEqualTo(new VnTime.IsoWeek(2026, 28));
    assertThat(VnTime.isoWeekOf(LocalDate.of(2026, 1, 1))).isEqualTo(new VnTime.IsoWeek(2026, 1));
  }

  @Test
  @DisplayName("ISO week: year-end and new-year days belong to the week their Thursday falls in")
  void isoWeekYearBoundary() {
    assertThat(VnTime.isoWeekOf(LocalDate.of(2024, 12, 30))).isEqualTo(new VnTime.IsoWeek(2025, 1));
    assertThat(VnTime.isoWeekOf(LocalDate.of(2027, 1, 1))).isEqualTo(new VnTime.IsoWeek(2026, 53));
    assertThat(VnTime.isoWeekOf(LocalDate.of(2027, 1, 3))).isEqualTo(new VnTime.IsoWeek(2026, 53));
    assertThat(VnTime.isoWeekOf(LocalDate.of(2027, 1, 4))).isEqualTo(new VnTime.IsoWeek(2027, 1));
  }
}
