package vn.pnj.meal.rules.deadline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.deadline.Deadlines.CloseAt;

/**
 * Week N starts Monday 2026-11-02. Week N-1 runs Monday 2026-10-26 to Sunday 2026-11-01, and its
 * regular closeAt is 00:00 Friday 2026-10-30 (shown as 23:59 Thursday).
 */
class CloseAtTest {
  private static final WeekdayTime CLOSE_RULE = DeadlinesTest.CLOSE_RULE;
  private static final int LATE_PUBLISH_HOURS = 24;
  private static final LocalTime LOCK_TIME = LocalTime.of(8, 0);
  private static final WeekdayTime WARN_RULE = new WeekdayTime(DayOfWeek.MONDAY, LocalTime.NOON);
  private static final LocalDate WEEK_START = LocalDate.of(2026, 11, 2);
  private static final DateTimeFormatter VN = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");

  private static Instant at(String date, String time) {
    return VnTime.vnToUtc(VnTime.parseDate(date), VnTime.parseTimeOfDay(time));
  }

  private static CloseAt publishAt(String date, String time) {
    return Deadlines.computeCloseAt(WEEK_START, at(date, time), CLOSE_RULE, LATE_PUBLISH_HOURS);
  }

  private static String vn(Instant instant) {
    return VnTime.toVn(instant).format(VN);
  }

  @Nested
  @DisplayName("closeAt at publish (BR-03, BR-04)")
  class AtPublish {
    @Test
    @DisplayName("BR-03: publishing on Monday of week N-1 keeps the regular closeAt (Friday 00:00)")
    void monday() {
      CloseAt r = publishAt("2026-10-26", "10:00");
      assertThat(vn(r.closeAt())).isEqualTo("2026-10-30 00:00");
      assertThat(r.latePublish()).isFalse();
    }

    @Test
    @DisplayName("BR-03: publishing at Thursday 23:59 does not extend the close")
    void thursday2359() {
      CloseAt r = publishAt("2026-10-29", "23:59");
      assertThat(vn(r.closeAt())).isEqualTo("2026-10-30 00:00");
      assertThat(r.latePublish()).isFalse();
    }

    @Test
    @DisplayName("BR-04: publishing at Friday 00:00 exactly is already late (closeAt is exclusive)")
    void fridayMidnight() {
      CloseAt r = publishAt("2026-10-30", "00:00");
      assertThat(r.latePublish()).isTrue();
      assertThat(vn(r.closeAt())).isEqualTo("2026-10-31 00:00");
    }

    @Test
    @DisplayName("BR-04: publishing at Friday 00:01 gives closeAt = publish time + 24 hours")
    void friday0001() {
      CloseAt r = publishAt("2026-10-30", "00:01");
      assertThat(vn(r.closeAt())).isEqualTo("2026-10-31 00:01");
      assertThat(r.latePublish()).isTrue();
      assertThat(vn(r.regularCloseAt())).isEqualTo("2026-10-30 00:00");
    }

    @Test
    @DisplayName("BR-04: publishing on Saturday gives closeAt on Sunday at the same time")
    void saturday() {
      assertThat(vn(publishAt("2026-10-31", "09:30").closeAt())).isEqualTo("2026-11-01 09:30");
    }

    @Test
    @DisplayName("BR-04: publishing on Sunday 20:00 gives closeAt Monday 20:00 of week N")
    void sunday() {
      CloseAt r = publishAt("2026-11-01", "20:00");
      assertThat(vn(r.closeAt())).isEqualTo("2026-11-02 20:00");
      assertThat(r.latePublish()).isTrue();
    }

    @Test
    @DisplayName("BR-04: the extension comes from registration.latePublishHours, not from code")
    void fromSetting() {
      CloseAt r = Deadlines.computeCloseAt(WEEK_START, at("2026-10-30", "10:00"), CLOSE_RULE, 6);
      assertThat(vn(r.closeAt())).isEqualTo("2026-10-30 16:00");
    }
  }

  @Nested
  @DisplayName("BR-05: a late closeAt never pushes a day past its own lock")
  class DayLock {
    private final Instant sundayPublish = publishAt("2026-11-01", "20:00").closeAt();

    @Test
    @DisplayName("BR-05: Monday still locks at its own 08:00 when closeAt is Monday 20:00")
    void monday() {
      assertThat(vn(Deadlines.dayFreeEditDeadline(WEEK_START, sundayPublish, LOCK_TIME)))
          .isEqualTo("2026-11-02 08:00");
    }

    @Test
    @DisplayName(
        "BR-05: Tuesday is bounded by the closeAt (Monday 20:00), which comes before its lock")
    void tuesday() {
      assertThat(
              vn(Deadlines.dayFreeEditDeadline(WEEK_START.plusDays(1), sundayPublish, LOCK_TIME)))
          .isEqualTo("2026-11-02 20:00");
    }

    @Test
    @DisplayName("BR-05: with a regular closeAt every day is bounded by the closeAt")
    void regular() {
      Instant regular = publishAt("2026-10-26", "10:00").closeAt();
      assertThat(Deadlines.dayFreeEditDeadline(WEEK_START, regular, LOCK_TIME)).isEqualTo(regular);
    }

    @Test
    @DisplayName("BR-05: the day lock time is read from day.lockTime")
    void lockTime() {
      assertThat(vn(Deadlines.dayLockAt(WEEK_START, LocalTime.of(7, 30))))
          .isEqualTo("2026-11-02 07:30");
    }
  }

  @Nested
  @DisplayName("BR-06: menu-not-published warning")
  class Warning {
    @Test
    @DisplayName("BR-06: starts at 12:00 Monday of week N-1")
    void start() {
      assertThat(vn(Deadlines.menuPublishWarnAt(WEEK_START, WARN_RULE)))
          .isEqualTo("2026-10-26 12:00");
    }

    @Test
    @DisplayName("BR-06: is not due at 11:59 Monday, is due at 12:00, and stops once published")
    void due() {
      assertThat(
              Deadlines.isMenuPublishWarningDue(
                  at("2026-10-26", "11:59"), WEEK_START, WARN_RULE, false))
          .isFalse();
      assertThat(
              Deadlines.isMenuPublishWarningDue(
                  at("2026-10-26", "12:00"), WEEK_START, WARN_RULE, false))
          .isTrue();
      assertThat(
              Deadlines.isMenuPublishWarningDue(
                  at("2026-10-29", "16:00"), WEEK_START, WARN_RULE, false))
          .isTrue();
      assertThat(
              Deadlines.isMenuPublishWarningDue(
                  at("2026-10-29", "16:00"), WEEK_START, WARN_RULE, true))
          .isFalse();
    }

    @Test
    @DisplayName("BR-06: the target week is the Monday after the current Vietnam week")
    void upcoming() {
      assertThat(Deadlines.upcomingWeekStart(at("2026-10-26", "12:00"))).isEqualTo(WEEK_START);
      // Sunday 23:30 VN still belongs to week N-1.
      assertThat(Deadlines.upcomingWeekStart(at("2026-11-01", "23:30"))).isEqualTo(WEEK_START);
      assertThat(Deadlines.upcomingWeekStart(at("2026-11-02", "00:00")))
          .isEqualTo(LocalDate.of(2026, 11, 9));
    }
  }
}
