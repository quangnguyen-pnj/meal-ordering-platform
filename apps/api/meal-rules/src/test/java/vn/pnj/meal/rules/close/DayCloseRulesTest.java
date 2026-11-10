package vn.pnj.meal.rules.close;

import static org.assertj.core.api.Assertions.assertThat;

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
import vn.pnj.meal.rules.attendance.AttendanceRules.PresenceState;
import vn.pnj.meal.rules.close.Adjustments.ErrorCode;
import vn.pnj.meal.rules.close.Adjustments.Line;
import vn.pnj.meal.rules.close.DayCloseRules.AdjustmentWindow;
import vn.pnj.meal.rules.close.DayCloseRules.FinalizeCheck;

class DayCloseRulesTest {
  private static final LocalDate DAY = LocalDate.of(2026, 11, 3);
  private static final LocalTime CLOSE = LocalTime.of(8, 30);
  private static final LocalTime LOCK = LocalTime.of(8, 0);
  private static final LocalTime AUTO = LocalTime.of(12, 0);
  private static final LocalTime DEADLINE = LocalTime.of(10, 30);

  private static Instant at(String time) {
    return VnTime.vnToUtc(DAY, VnTime.parseTimeOfDay(time));
  }

  @Nested
  @DisplayName("BR-43: finalization")
  class Finalize {
    @Test
    @DisplayName("BR-43: the HC admin can finalize from 08:30 on day D, not at 08:29")
    void from0830() {
      assertThat(DayCloseRules.canFinalize(at("08:29"), DAY, CLOSE, LOCK, false))
          .isEqualTo(FinalizeCheck.TOO_EARLY);
      assertThat(DayCloseRules.canFinalize(at("08:30"), DAY, CLOSE, LOCK, false))
          .isEqualTo(FinalizeCheck.OK);
    }

    @Test
    @DisplayName("BR-43: a day is finalized once; a later day may still finalize a missed one")
    void once() {
      assertThat(DayCloseRules.canFinalize(at("09:00"), DAY, CLOSE, LOCK, true))
          .isEqualTo(FinalizeCheck.ALREADY_FINALIZED);
      assertThat(
              DayCloseRules.canFinalize(
                  VnTime.vnToUtc(DAY.plusDays(1), LocalTime.of(9, 0)), DAY, CLOSE, LOCK, false))
          .isEqualTo(FinalizeCheck.OK);
    }

    @Test
    @DisplayName("BR-43: never before the day's lock, even when close.time is set earlier")
    void notBeforeLock() {
      assertThat(DayCloseRules.finalizeOpensAt(DAY, LocalTime.of(7, 30), LOCK))
          .isEqualTo(at("08:00"));
    }

    @Test
    @DisplayName("BR-43: provisional cancellations become cancellations; other states stay")
    void presence() {
      assertThat(DayCloseRules.presenceAtFinalization(PresenceState.PROVISIONAL_ABSENT))
          .isEqualTo(PresenceState.ABSENT);
      assertThat(DayCloseRules.presenceAtFinalization(PresenceState.PRESENT))
          .isEqualTo(PresenceState.PRESENT);
      assertThat(DayCloseRules.presenceAtFinalization(PresenceState.UNKNOWN))
          .isEqualTo(PresenceState.UNKNOWN);
    }

    @Test
    @DisplayName("BR-43: the HC admin is reminded from 08:45 on day D while not finalized")
    void reminder() {
      LocalTime remind = LocalTime.of(8, 45);
      assertThat(DayCloseRules.isReminderDue(at("08:44"), DAY, remind, false)).isFalse();
      assertThat(DayCloseRules.isReminderDue(at("08:45"), DAY, remind, false)).isTrue();
      assertThat(DayCloseRules.isReminderDue(at("09:00"), DAY, remind, true)).isFalse();
    }

    @Test
    @DisplayName(
        "BR-43: a day not finalized by 12:00 is finalized by the system, never before 08:30")
    void auto() {
      Instant autoAt = DayCloseRules.autoFinalizeAt(DAY, AUTO, CLOSE, LOCK);
      assertThat(autoAt).isEqualTo(at("12:00"));
      assertThat(DayCloseRules.isAutoFinalizeDue(at("11:59"), autoAt, false)).isFalse();
      assertThat(DayCloseRules.isAutoFinalizeDue(at("12:00"), autoAt, false)).isTrue();
      assertThat(DayCloseRules.isAutoFinalizeDue(at("12:00"), autoAt, true)).isFalse();
      assertThat(DayCloseRules.autoFinalizeAt(DAY, LocalTime.of(8, 0), CLOSE, LOCK))
          .isEqualTo(at("08:30"));
    }

    @Test
    @DisplayName("BR-24 / BR-33: admin changes stop at finalization or at the auto-finalization")
    void adminChanges() {
      Instant autoAt = at("12:00");
      assertThat(DayCloseRules.openForAdminChanges(at("08:20"), false, autoAt)).isTrue();
      assertThat(DayCloseRules.openForAdminChanges(at("08:31"), true, autoAt)).isFalse();
      assertThat(DayCloseRules.openForAdminChanges(at("12:00"), false, autoAt)).isFalse();
    }
  }

  @Nested
  @DisplayName("BR-30 / BR-32 (v1.4): attendance after finalization")
  class AttendanceAfter {
    @Test
    @DisplayName("BR-32: after finalization syncs run until 10:30, then not at all")
    void untilDeadline() {
      assertThat(DayCloseRules.attendanceSyncAllowed(at("10:29"), DAY, true, DEADLINE)).isTrue();
      assertThat(DayCloseRules.attendanceSyncAllowed(at("10:30"), DAY, true, DEADLINE)).isFalse();
    }

    @Test
    @DisplayName("BR-30: before finalization syncs go on after 10:30; never on another day")
    void beforeFinalization() {
      assertThat(DayCloseRules.attendanceSyncAllowed(at("11:00"), DAY, false, DEADLINE)).isTrue();
      assertThat(
              DayCloseRules.attendanceSyncAllowed(
                  VnTime.vnToUtc(DAY.plusDays(1), LocalTime.of(8, 0)), DAY, false, DEADLINE))
          .isFalse();
    }
  }

  @Nested
  @DisplayName("BR-46: adjustments after finalization")
  class AdjustmentRules {
    @Test
    @DisplayName("BR-46: an adjustment is accepted until 10:30 on day D, only once finalized")
    void window() {
      assertThat(DayCloseRules.adjustmentWindow(at("09:00"), DAY, false, DEADLINE, false))
          .isEqualTo(AdjustmentWindow.NOT_FINALIZED);
      assertThat(DayCloseRules.adjustmentWindow(at("10:29"), DAY, true, DEADLINE, false))
          .isEqualTo(AdjustmentWindow.OPEN);
      assertThat(DayCloseRules.adjustmentWindow(at("10:30"), DAY, true, DEADLINE, false))
          .isEqualTo(AdjustmentWindow.PAST_DEADLINE);
      assertThat(DayCloseRules.adjustmentWindow(at("09:00"), DAY, true, DEADLINE, true))
          .isEqualTo(AdjustmentWindow.DELIVERY_CONFIRMED);
    }

    private static final Map<String, Set<String>> MENU =
        Map.of("E1", Set.of("ca", "chay"), "E2", Set.of("ca", "chay"));
    private static final Map<String, Map<String, Integer>> EFFECTIVE =
        Map.of("E1", Map.of("ca", 1));

    private static List<ErrorCode> codes(List<Line> lines, String reason, boolean caterer) {
      return Adjustments.validate(lines, reason, caterer, MENU, EFFECTIVE).stream()
          .map(Adjustments.Error::code)
          .toList();
    }

    @Test
    @DisplayName("BR-46: adding a meal needs a reason and the caterer's confirmation")
    void addNeedsCaterer() {
      List<Line> add = List.of(new Line("E2", "ca", 2));
      assertThat(codes(add, "Nhân viên mới", true)).isEmpty();
      assertThat(codes(add, "Nhân viên mới", false))
          .containsExactly(ErrorCode.CATERER_CONFIRMATION_REQUIRED);
      assertThat(codes(add, "  ", true)).containsExactly(ErrorCode.REASON_REQUIRED);
    }

    @Test
    @DisplayName("BR-46: removing meals needs a reason but no caterer tick")
    void remove() {
      assertThat(codes(List.of(new Line("E1", "ca", -1)), "Về sớm", false)).isEmpty();
    }

    @Test
    @DisplayName("BR-46: an employee's effective count never goes below zero")
    void belowZero() {
      assertThat(codes(List.of(new Line("E1", "ca", -2)), "x", false))
          .containsExactly(ErrorCode.BELOW_ZERO);
      assertThat(codes(List.of(new Line("E2", "chay", -1)), "x", false))
          .containsExactly(ErrorCode.BELOW_ZERO);
    }

    @Test
    @DisplayName(
        "BR-46: lines must be whole, non-zero, unique, on the day's menu, for a known employee")
    void lines() {
      assertThat(codes(List.of(), "x", false)).containsExactly(ErrorCode.NO_LINES);
      assertThat(codes(List.of(new Line("E1", "ca", 0)), "x", false))
          .containsExactly(ErrorCode.DELTA_ZERO);
      assertThat(codes(List.of(new Line("E1", "ca", 1.5)), "x", true))
          .containsExactly(ErrorCode.DELTA_NOT_INTEGER);
      assertThat(codes(List.of(new Line("E2", "ca", 1), new Line("E2", "ca", 1)), "x", true))
          .containsExactly(ErrorCode.DUPLICATE_LINE);
      assertThat(codes(List.of(new Line("E2", "chao", 1)), "x", true))
          .containsExactly(ErrorCode.ITEM_NOT_ON_MENU);
      assertThat(codes(List.of(new Line("E9", "ca", 1)), "x", true))
          .containsExactly(ErrorCode.EMPLOYEE_UNKNOWN);
    }

    @Test
    @DisplayName("BR-46: effective count = final count + the adjustment lines")
    void effective() {
      assertThat(Reconciliation.effective(10, 2)).isEqualTo(12);
      assertThat(Reconciliation.effective(10, -3)).isEqualTo(7);
    }
  }

  @Nested
  @DisplayName("BR-40 / BR-42: reconciliation per meal")
  class PerMeal {
    @Test
    @DisplayName("BR-40: a meal is Matched when actual equals forecast, else Mismatched")
    void status() {
      assertThat(Reconciliation.status(5, 5)).isEqualTo(Reconciliation.MealStatus.MATCHED);
      assertThat(Reconciliation.status(5, 4)).isEqualTo(Reconciliation.MealStatus.MISMATCHED);
    }

    @Test
    @DisplayName("BR-42: warn above 10% deviation from forecast; exactly 10% does not warn")
    void deviation() {
      assertThat(Reconciliation.deviates(100, 110, 10)).isFalse();
      assertThat(Reconciliation.deviates(100, 111, 10)).isTrue();
      assertThat(Reconciliation.deviates(100, 89, 10)).isTrue();
      assertThat(Reconciliation.deviates(0, 1, 10)).isTrue();
      assertThat(Reconciliation.deviates(0, 0, 10)).isFalse();
      assertThat(Reconciliation.deviationPct(40, 45)).isEqualTo(12.5);
      assertThat(Reconciliation.deviationPct(0, 3)).isNull();
    }
  }
}
