package vn.pnj.meal.rules.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.pnj.meal.rules.attendance.AttendanceRules.PresenceState.ABSENT;
import static vn.pnj.meal.rules.attendance.AttendanceRules.PresenceState.PRESENT;
import static vn.pnj.meal.rules.attendance.AttendanceRules.PresenceState.PROVISIONAL_ABSENT;
import static vn.pnj.meal.rules.attendance.AttendanceRules.PresenceState.UNKNOWN;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.attendance.AttendanceRules.CutoffOutcome;

class AttendanceRulesTest {
  private static final LocalDate DAY = LocalDate.of(2026, 11, 3);
  private static final LocalTime CUTOFF = LocalTime.of(8, 10);
  private static final LocalTime POLL_START = LocalTime.of(7, 0);

  private static Instant at(String time) {
    return VnTime.vnToUtc(DAY, VnTime.parseTimeOfDay(time));
  }

  private static Instant at(String time, int seconds) {
    return at(time).plus(Duration.ofSeconds(seconds));
  }

  @Nested
  @DisplayName("BR-30: present = a check-in by the cutoff or a manager's DMP confirmation")
  class Present {
    @Test
    @DisplayName("BR-30: a check-in at 07:45 is present")
    void onTime() {
      assertThat(AttendanceRules.isPresent(at("07:45"), false, DAY, CUTOFF)).isTrue();
    }

    @Test
    @DisplayName("BR-30: a check-in at 08:10:59 is still in time; 08:11:00 is not")
    void cutoffMinute() {
      assertThat(AttendanceRules.checkInInTime(at("08:10", 59), DAY, CUTOFF)).isTrue();
      assertThat(AttendanceRules.checkInInTime(at("08:11"), DAY, CUTOFF)).isFalse();
    }

    @Test
    @DisplayName("BR-30: a check-in at 08:15 is not present")
    void late() {
      assertThat(AttendanceRules.isPresent(at("08:15"), false, DAY, CUTOFF)).isFalse();
    }

    @Test
    @DisplayName("BR-30: no check-in at all is not present")
    void none() {
      assertThat(AttendanceRules.isPresent(null, false, DAY, CUTOFF)).isFalse();
    }

    @Test
    @DisplayName("BR-30: a check-in the day before does not count for day D")
    void otherDay() {
      Instant yesterday = VnTime.vnToUtc(DAY.minusDays(1), LocalTime.of(7, 45));
      assertThat(AttendanceRules.checkInInTime(yesterday, DAY, CUTOFF)).isFalse();
    }

    @Test
    @DisplayName("BR-30: a manager's DMP confirmation is present without any check-in")
    void confirmedNoCheckIn() {
      assertThat(AttendanceRules.isPresent(null, true, DAY, CUTOFF)).isTrue();
    }

    @Test
    @DisplayName("BR-30: a manager's DMP confirmation is present whatever the arrival time")
    void confirmedLate() {
      assertThat(AttendanceRules.isPresent(at("09:30"), true, DAY, CUTOFF)).isTrue();
    }

    @Test
    @DisplayName("BR-30: the earliest check-in of day D is used; other days are ignored")
    void earliest() {
      Instant yesterday = VnTime.vnToUtc(DAY.minusDays(1), LocalTime.of(7, 0));
      assertThat(
              AttendanceRules.earliestCheckIn(
                  Arrays.asList(at("12:05"), null, at("07:52"), yesterday), DAY))
          .isEqualTo(at("07:52"));
      assertThat(AttendanceRules.earliestCheckIn(List.of(yesterday), DAY)).isNull();
    }
  }

  @Nested
  @DisplayName("BR-31 / BR-32: provisional cancellation and automatic restore")
  class States {
    @Test
    @DisplayName(
        "BR-31: at 08:10 an employee with meals and no check-in is provisionally cancelled")
    void cancelled() {
      assertThat(AttendanceRules.cancelledAtCutoff(UNKNOWN, true)).isTrue();
    }

    @Test
    @DisplayName("BR-31: employees without meals or already present are not cancelled")
    void notCancelled() {
      assertThat(AttendanceRules.cancelledAtCutoff(UNKNOWN, false)).isFalse();
      assertThat(AttendanceRules.cancelledAtCutoff(PRESENT, true)).isFalse();
    }

    @Test
    @DisplayName("BR-31: meals of a provisionally cancelled or absent employee do not count")
    void mealsCount() {
      assertThat(AttendanceRules.mealsCount(PROVISIONAL_ABSENT)).isFalse();
      assertThat(AttendanceRules.mealsCount(ABSENT)).isFalse();
      assertThat(AttendanceRules.mealsCount(UNKNOWN)).isTrue();
      assertThat(AttendanceRules.mealsCount(PRESENT)).isTrue();
    }

    @Test
    @DisplayName("BR-32: a 08:05 check-in arriving at 08:20 restores automatically")
    void lateRecordRestores() {
      boolean present = AttendanceRules.isPresent(at("08:05"), false, DAY, CUTOFF);
      assertThat(AttendanceRules.afterSync(PROVISIONAL_ABSENT, present)).isEqualTo(PRESENT);
    }

    @Test
    @DisplayName("BR-32: a 08:15 check-in does not restore")
    void lateCheckInStays() {
      boolean present = AttendanceRules.isPresent(at("08:15"), false, DAY, CUTOFF);
      assertThat(AttendanceRules.afterSync(PROVISIONAL_ABSENT, present))
          .isEqualTo(PROVISIONAL_ABSENT);
    }

    @Test
    @DisplayName("BR-32: a manager's DMP confirmation before finalization restores, late or not")
    void dmpConfirmationRestores() {
      boolean present = AttendanceRules.isPresent(at("08:20"), true, DAY, CUTOFF);
      assertThat(AttendanceRules.afterSync(PROVISIONAL_ABSENT, present)).isEqualTo(PRESENT);
    }

    @Test
    @DisplayName("BR-32: a sync never takes presence back (Q27)")
    void neverRevoked() {
      assertThat(AttendanceRules.afterSync(PRESENT, false)).isEqualTo(PRESENT);
      assertThat(AttendanceRules.afterSync(UNKNOWN, false)).isEqualTo(UNKNOWN);
    }

    @Test
    @DisplayName("BR-46: after finalization a late check-in or confirmation restores nothing")
    void absentIsFinal() {
      assertThat(AttendanceRules.afterSync(ABSENT, true)).isEqualTo(ABSENT);
    }
  }

  @Nested
  @DisplayName("BR-34: safety check before bulk cancellation")
  class Safety {
    @Test
    @DisplayName("BR-34: a failed latest sync holds the cancellation")
    void syncFailed() {
      assertThat(AttendanceRules.safetyCheck(false, 100, 95, 50))
          .isEqualTo(CutoffOutcome.HELD_SYNC_FAILED);
    }

    @Test
    @DisplayName("BR-34: under 50% present holds the cancellation; exactly 50% cancels")
    void threshold() {
      assertThat(AttendanceRules.safetyCheck(true, 100, 49, 50))
          .isEqualTo(CutoffOutcome.HELD_TOO_FEW_PRESENT);
      assertThat(AttendanceRules.safetyCheck(true, 100, 50, 50)).isEqualTo(CutoffOutcome.CANCELLED);
    }

    @Test
    @DisplayName("BR-34: a day without meals has nothing to cancel and passes")
    void noMeals() {
      assertThat(AttendanceRules.safetyCheck(true, 0, 0, 50)).isEqualTo(CutoffOutcome.CANCELLED);
    }
  }

  @Nested
  @DisplayName("BR-30 / BR-31: when the jobs run")
  class Schedule {
    @Test
    @DisplayName("BR-30: polling starts at 07:00 and repeats every 5 minutes")
    void poll() {
      assertThat(AttendanceRules.isPollDue(at("06:59"), DAY, POLL_START, 5, null, false)).isFalse();
      assertThat(AttendanceRules.isPollDue(at("07:00"), DAY, POLL_START, 5, null, false)).isTrue();
      assertThat(AttendanceRules.isPollDue(at("07:04"), DAY, POLL_START, 5, at("07:00"), false))
          .isFalse();
      assertThat(AttendanceRules.isPollDue(at("07:05"), DAY, POLL_START, 5, at("07:00"), false))
          .isTrue();
    }

    @Test
    @DisplayName("BR-30: polling stops at finalization and on other days")
    void pollStops() {
      assertThat(AttendanceRules.isPollDue(at("08:40"), DAY, POLL_START, 5, null, true)).isFalse();
      assertThat(
              AttendanceRules.isPollDue(at("07:30"), DAY.plusDays(1), POLL_START, 5, null, false))
          .isFalse();
    }

    @Test
    @DisplayName("BR-31: the cutoff run is due from 08:10, once")
    void cutoff() {
      assertThat(AttendanceRules.isCutoffDue(at("08:09", 59), DAY, CUTOFF, false, false)).isFalse();
      assertThat(AttendanceRules.isCutoffDue(at("08:10"), DAY, CUTOFF, false, false)).isTrue();
      assertThat(AttendanceRules.isCutoffDue(at("08:20"), DAY, CUTOFF, true, false)).isFalse();
    }

    @Test
    @DisplayName("BR-33: an admin confirms presence on day D until finalization")
    void confirmWindow() {
      assertThat(AttendanceRules.canConfirmPresence(at("08:25"), DAY, false)).isTrue();
      assertThat(AttendanceRules.canConfirmPresence(at("08:35"), DAY, true)).isFalse();
      assertThat(
              AttendanceRules.canConfirmPresence(
                  VnTime.vnToUtc(DAY.plusDays(1), LocalTime.of(8, 0)), DAY, false))
          .isFalse();
    }
  }
}
