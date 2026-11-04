package vn.pnj.meal.rules.deadline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.deadline.Deadlines.CloseAt;
import vn.pnj.meal.rules.deadline.Deadlines.PublishWindow;

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
      assertThat(r.window()).isEqualTo(PublishWindow.ON_TIME);
    }

    @Test
    @DisplayName("BR-03: publishing at Thursday 23:59 does not extend the close")
    void thursday2359() {
      CloseAt r = publishAt("2026-10-29", "23:59");
      assertThat(vn(r.closeAt())).isEqualTo("2026-10-30 00:00");
      assertThat(r.window()).isEqualTo(PublishWindow.ON_TIME);
    }

    @Test
    @DisplayName("BR-04: publishing at Friday 00:00 exactly is already late (closeAt is exclusive)")
    void fridayMidnight() {
      CloseAt r = publishAt("2026-10-30", "00:00");
      assertThat(r.window()).isEqualTo(PublishWindow.LATE);
      assertThat(vn(r.closeAt())).isEqualTo("2026-10-31 00:00");
    }

    @Test
    @DisplayName("BR-04: publishing at Friday 00:01 suggests closeAt = publish time + 24 hours")
    void friday0001() {
      CloseAt r = publishAt("2026-10-30", "00:01");
      assertThat(vn(r.closeAt())).isEqualTo("2026-10-31 00:01");
      assertThat(r.window()).isEqualTo(PublishWindow.LATE);
      assertThat(vn(r.regularCloseAt())).isEqualTo("2026-10-30 00:00");
    }

    @Test
    @DisplayName("BR-04: publishing on Saturday suggests closeAt on Sunday at the same time")
    void saturday() {
      assertThat(vn(publishAt("2026-10-31", "09:30").closeAt())).isEqualTo("2026-11-01 09:30");
    }

    @Test
    @DisplayName("BR-04: publishing on Sunday 20:00 suggests at most 00:00 Monday of week N")
    void sunday() {
      CloseAt r = publishAt("2026-11-01", "20:00");
      assertThat(vn(r.closeAt())).isEqualTo("2026-11-02 00:00");
      assertThat(r.window()).isEqualTo(PublishWindow.LATE);
    }

    @Test
    @DisplayName("BR-04: the suggestion comes from registration.latePublishHours, not from code")
    void fromSetting() {
      CloseAt r = Deadlines.computeCloseAt(WEEK_START, at("2026-10-30", "10:00"), CLOSE_RULE, 6);
      assertThat(vn(r.closeAt())).isEqualTo("2026-10-30 16:00");
    }

    @Test
    @DisplayName("BR-04: publishing at Sunday 23:59 of week N-1 is still a late publish")
    void sunday2359() {
      assertThat(publishAt("2026-11-01", "23:59").window()).isEqualTo(PublishWindow.LATE);
    }

    @Test
    @DisplayName("BR-04: publishing at 00:00 Monday of week N has no forecast: closeAt = publish")
    void mondayMidnight() {
      CloseAt r = publishAt("2026-11-02", "00:00");
      assertThat(r.window()).isEqualTo(PublishWindow.IN_WEEK);
      assertThat(vn(r.closeAt())).isEqualTo("2026-11-02 00:00");
    }

    @Test
    @DisplayName("BR-04: publishing week 45's menu on Wednesday of week 45 has no forecast")
    void inWeek() {
      CloseAt r = publishAt("2026-11-04", "10:15");
      assertThat(r.window()).isEqualTo(PublishWindow.IN_WEEK);
      assertThat(vn(r.closeAt())).isEqualTo("2026-11-04 10:15");
    }
  }

  @Nested
  @DisplayName("BR-04: the closeAt the HC admin picks for a late publish")
  class ChosenCloseAt {
    private final Instant saturday = at("2026-10-31", "09:30");

    @Test
    @DisplayName("BR-04: a closeAt later the same day is accepted")
    void sameDay() {
      assertThat(Deadlines.isValidLateCloseAt(WEEK_START, saturday, at("2026-10-31", "17:00")))
          .isTrue();
    }

    @Test
    @DisplayName("BR-04: 00:00 Monday of week N is the latest accepted closeAt")
    void mondayMidnight() {
      assertThat(Deadlines.isValidLateCloseAt(WEEK_START, saturday, at("2026-11-02", "00:00")))
          .isTrue();
      assertThat(Deadlines.isValidLateCloseAt(WEEK_START, saturday, at("2026-11-02", "00:01")))
          .isFalse();
    }

    @Test
    @DisplayName("BR-04: a closeAt at or before the publish time is refused")
    void notInThePast() {
      assertThat(Deadlines.isValidLateCloseAt(WEEK_START, saturday, saturday)).isFalse();
      assertThat(Deadlines.isValidLateCloseAt(WEEK_START, saturday, at("2026-10-31", "09:00")))
          .isFalse();
    }
  }

  @Nested
  @DisplayName("BR-04: a week whose serving days have all locked cannot be published")
  class WeekOver {
    private final List<LocalDate> monToFri =
        List.of(WEEK_START, WEEK_START.plusDays(1), WEEK_START.plusDays(4));

    private boolean overAt(List<LocalDate> days, String date, String time) {
      return Deadlines.isWeekOver(WEEK_START, days, at(date, time), LOCK_TIME);
    }

    @Test
    @DisplayName("BR-04: on Wednesday of week N the week is not over while Friday is still open")
    void midWeek() {
      assertThat(overAt(monToFri, "2026-11-04", "10:00")).isFalse();
    }

    @Test
    @DisplayName("BR-04: the week is over from 08:00 of its last serving day, not at 07:59")
    void lastDayLock() {
      assertThat(overAt(monToFri, "2026-11-06", "07:59")).isFalse();
      assertThat(overAt(monToFri, "2026-11-06", "08:00")).isTrue();
    }

    @Test
    @DisplayName("BR-04: a past week (week 44 seen from week 45) is over")
    void pastWeek() {
      LocalDate week44 = WEEK_START.minusDays(7);
      assertThat(
              Deadlines.isWeekOver(
                  week44,
                  List.of(week44, week44.plusDays(4)),
                  at("2026-11-03", "10:00"),
                  LOCK_TIME))
          .isTrue();
    }

    @Test
    @DisplayName("BR-04: an enabled Saturday keeps the week open until its own lock")
    void saturday() {
      List<LocalDate> withSaturday = List.of(WEEK_START, WEEK_START.plusDays(5));
      assertThat(overAt(withSaturday, "2026-11-06", "09:00")).isFalse();
      assertThat(overAt(withSaturday, "2026-11-07", "08:00")).isTrue();
    }

    @Test
    @DisplayName("BR-04: a week with no serving day is over once its Sunday has locked")
    void noServingDay() {
      assertThat(overAt(List.of(), "2026-11-08", "07:59")).isFalse();
      assertThat(overAt(List.of(), "2026-11-08", "08:00")).isTrue();
    }
  }

  @Nested
  @DisplayName("BR-05: a closeAt inside week N never pushes a day past its own lock")
  class DayLock {
    // A menu published in week N at Monday 20:00 has closeAt = Monday 20:00.
    private final Instant mondayPublish = publishAt("2026-11-02", "20:00").closeAt();

    @Test
    @DisplayName("BR-05: Monday still locks at its own 08:00 when closeAt is Monday 20:00")
    void monday() {
      assertThat(vn(Deadlines.dayFreeEditDeadline(WEEK_START, mondayPublish, LOCK_TIME)))
          .isEqualTo("2026-11-02 08:00");
    }

    @Test
    @DisplayName(
        "BR-05: Tuesday is bounded by the closeAt (Monday 20:00), which comes before its lock")
    void tuesday() {
      assertThat(
              vn(Deadlines.dayFreeEditDeadline(WEEK_START.plusDays(1), mondayPublish, LOCK_TIME)))
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
