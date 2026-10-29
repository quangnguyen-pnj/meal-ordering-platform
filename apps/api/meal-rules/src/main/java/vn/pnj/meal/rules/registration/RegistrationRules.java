package vn.pnj.meal.rules.registration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.deadline.Deadlines;

/**
 * Registration rules: a day's selection (BR-20), who may change a day and when (BR-21 to BR-25),
 * the forecast and its differences (BR-07, BR-23) and leavers (BR-36).
 */
public final class RegistrationRules {
  private RegistrationRules() {}

  public enum Status {
    ACTIVE,
    NEEDS_RESELECT,
    CANCELLED
  }

  /**
   * One line of a day's selection as sent: a quantity of one menu item (BR-20). The quantity is a
   * double so that 1.5 can be reported per line instead of failing the whole body.
   */
  public record Line(String menuItemId, double quantity) {}

  public enum SelectionErrorCode {
    /** BR-20: quantities are non-negative. */
    QUANTITY_NEGATIVE,
    /** BR-20: quantities are integers. */
    QUANTITY_NOT_INTEGER,
    /** BR-20: the item must be a published menu item of that day and canteen. */
    ITEM_NOT_ON_MENU,
    /** The same item twice in one selection is ambiguous. */
    DUPLICATE_ITEM,
    /** Only when a cap is configured; the MVP has none (BR-20). */
    OVER_LIMIT
  }

  public record SelectionError(SelectionErrorCode code, String menuItemId) {}

  /**
   * BR-20: no portion limit. Per employee, day and session an employee may pick any dishes on that
   * day's menu, several dishes and several types, each with a non-negative integer quantity. Zero
   * or no line means not registered.
   *
   * @param maxPerDay the hook BR-20 keeps for a future cap; null (no cap) everywhere today
   * @return every error; empty when the selection is valid
   */
  public static List<SelectionError> validateDaySelection(
      List<Line> lines, Set<String> menuItemIds, Integer maxPerDay) {
    List<SelectionError> errors = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Line line : lines) {
      if (!seen.add(line.menuItemId())) {
        errors.add(new SelectionError(SelectionErrorCode.DUPLICATE_ITEM, line.menuItemId()));
        continue;
      }
      if (line.quantity() != Math.rint(line.quantity())) {
        errors.add(new SelectionError(SelectionErrorCode.QUANTITY_NOT_INTEGER, line.menuItemId()));
      } else if (line.quantity() < 0) {
        errors.add(new SelectionError(SelectionErrorCode.QUANTITY_NEGATIVE, line.menuItemId()));
      }
      if (!menuItemIds.contains(line.menuItemId())) {
        errors.add(new SelectionError(SelectionErrorCode.ITEM_NOT_ON_MENU, line.menuItemId()));
      }
    }
    if (errors.isEmpty() && maxPerDay != null && totalMeals(lines) > maxPerDay) {
      String last = lines.isEmpty() ? "" : lines.getLast().menuItemId();
      errors.add(new SelectionError(SelectionErrorCode.OVER_LIMIT, last));
    }
    return List.copyOf(errors);
  }

  public static List<SelectionError> validateDaySelection(
      List<Line> lines, Set<String> menuItemIds) {
    return validateDaySelection(lines, menuItemIds, null);
  }

  public static int totalMeals(List<Line> lines) {
    return (int) lines.stream().mapToDouble(Line::quantity).filter(q -> q > 0).sum();
  }

  /** Positive quantities by menu item, for a validated selection. */
  public static Map<String, Integer> quantitiesOf(List<Line> lines) {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (Line line : lines) {
      if (line.quantity() > 0) {
        out.merge(line.menuItemId(), (int) line.quantity(), Integer::sum);
      }
    }
    return out;
  }

  /**
   * What an employee may do with their own meals of a serving day (BR-22 to BR-24).
   *
   * <ul>
   *   <li>OPEN: before closeAt and before the day's lock; changes form the forecast (BR-22).
   *   <li>AFTER_CLOSE: closeAt has passed (or the forecast was already taken) but the day's lock
   *       has not; add, change and remove are still allowed, also without a forecast, and every
   *       change is a difference from the forecast (BR-23).
   *   <li>LOCKED: at or after {@code day.lockTime} on day D, employees cannot change anything
   *       (BR-24). A late closeAt never pushes a day past its own lock (BR-05).
   * </ul>
   */
  public enum EmployeeDayWindow {
    OPEN,
    AFTER_CLOSE,
    LOCKED
  }

  /**
   * @param forecastTaken true once the forecast snapshot exists (BR-07), even if {@code now} reads
   *     earlier
   */
  public static EmployeeDayWindow employeeDayWindow(
      Instant now, LocalDate date, Instant closeAt, LocalTime lockTime, boolean forecastTaken) {
    if (!now.isBefore(Deadlines.dayLockAt(date, lockTime))) {
      return EmployeeDayWindow.LOCKED;
    }
    if (forecastTaken || !now.isBefore(closeAt)) {
      return EmployeeDayWindow.AFTER_CLOSE;
    }
    return EmployeeDayWindow.OPEN;
  }

  /**
   * What a dept or HC admin may do for an employee's day.
   *
   * <ul>
   *   <li>ON_BEHALF: until the day's lock, register and edit on behalf like the employee (BR-21).
   *   <li>EXTRA: after the lock and before finalization, only add meals flagged "Extra" with a
   *       reason (BR-25). Editing or reducing after the lock is /admin/today (P3).
   *   <li>CLOSED: nothing through registration; later changes go through adjustments (BR-46, P4).
   * </ul>
   *
   * <p>TODO(BR-25): finalization (DayClose) arrives in P4. Until then the extra window also ends
   * when day D ends in Vietnam time, so past days never change (open-questions Q20).
   */
  public enum AdminDayWindow {
    ON_BEHALF,
    EXTRA,
    CLOSED
  }

  public static AdminDayWindow adminDayWindow(
      Instant now, LocalDate date, LocalTime lockTime, boolean finalized) {
    if (now.isBefore(Deadlines.dayLockAt(date, lockTime))) {
      return AdminDayWindow.ON_BEHALF;
    }
    if (finalized) {
      return AdminDayWindow.CLOSED;
    }
    return now.isBefore(VnTime.vnToUtc(date.plusDays(1), LocalTime.MIDNIGHT))
        ? AdminDayWindow.EXTRA
        : AdminDayWindow.CLOSED;
  }

  /**
   * BR-07 / BR-22: a registration is part of the forecast at closeAt when it is active with a
   * positive quantity. A line waiting for reselection (BR-14) is not a meal anyone chose from the
   * current menu, so it is left out (open-questions Q21).
   */
  public static boolean isForecastLine(Status status, int quantity) {
    return status == Status.ACTIVE && quantity > 0;
  }

  /**
   * @param difference actual - forecast
   */
  public record ComparisonLine(String menuItemId, int forecast, int actual, int difference) {}

  public record Comparison(
      List<ComparisonLine> lines, boolean changed, int forecastTotal, int actualTotal) {}

  /**
   * BR-23: after closeAt every change is a difference from the immutable forecast. Compares one
   * employee's (or one unit's) forecast with the current active quantities, per menu item.
   */
  public static Comparison compareWithForecast(
      Map<String, Integer> forecast, Map<String, Integer> actual) {
    Map<String, int[]> byItem = new LinkedHashMap<>();
    forecast.forEach((id, q) -> byItem.computeIfAbsent(id, k -> new int[2])[0] += q);
    actual.forEach((id, q) -> byItem.computeIfAbsent(id, k -> new int[2])[1] += Math.max(q, 0));
    List<ComparisonLine> lines = new ArrayList<>();
    byItem.forEach(
        (id, fa) -> {
          if (fa[0] != 0 || fa[1] != 0) {
            lines.add(new ComparisonLine(id, fa[0], fa[1], fa[1] - fa[0]));
          }
        });
    return new Comparison(
        List.copyOf(lines),
        lines.stream().anyMatch(l -> l.difference() != 0),
        positiveSum(forecast),
        positiveSum(actual));
  }

  private static int positiveSum(Map<String, Integer> quantities) {
    return quantities.values().stream().mapToInt(q -> Math.max(q, 0)).sum();
  }

  /**
   * BR-36: an employee who leaves loses all meals from their end date. Registrations of a day whose
   * lock has already passed are left to reconciliation, so locked days never change silently.
   */
  public static boolean isLostByLeaver(
      LocalDate date, LocalDate endDate, Instant now, LocalTime lockTime) {
    return !date.isBefore(endDate) && now.isBefore(Deadlines.dayLockAt(date, lockTime));
  }
}
