package vn.pnj.meal.rules.close;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * BR-46: an adjustment of a finalized day. Lines of employee, meal and a positive or negative
 * quantity, a required reason, and a required "Caterer confirmed" tick when any line adds meals.
 * FinalCount never changes; effective count = FinalCount + sum of adjustment lines.
 */
public final class Adjustments {
  private Adjustments() {}

  /**
   * One requested line. {@code delta} is read as a double so 1.5 is reported per line instead of
   * failing the whole body, as for registrations (BR-20).
   */
  public record Line(String employeeCode, String menuItemId, double delta) {}

  public enum ErrorCode {
    NO_LINES,
    REASON_REQUIRED,
    CATERER_CONFIRMATION_REQUIRED,
    DELTA_ZERO,
    DELTA_NOT_INTEGER,
    DUPLICATE_LINE,
    EMPLOYEE_UNKNOWN,
    ITEM_NOT_ON_MENU,
    BELOW_ZERO
  }

  /**
   * @param line 0-based index of the line, null for an error of the whole adjustment
   */
  public record Error(ErrorCode code, @Nullable Integer line) {}

  /**
   * Checks an adjustment against the day.
   *
   * @param menuByEmployee for each known employee code, the menu items of their canteen's day D (an
   *     employee absent from the map is unknown in this factory)
   * @param effective the current effective quantity per employee code and menu item (final line +
   *     earlier adjustments); missing means 0
   */
  public static List<Error> validate(
      List<Line> lines,
      @Nullable String reason,
      boolean catererConfirmed,
      Map<String, Set<String>> menuByEmployee,
      Map<String, Map<String, Integer>> effective) {
    List<Error> errors = new ArrayList<>();
    if (lines.isEmpty()) {
      errors.add(new Error(ErrorCode.NO_LINES, null));
    }
    if (reason == null || reason.isBlank()) {
      errors.add(new Error(ErrorCode.REASON_REQUIRED, null));
    }
    if (addsMeals(lines) && !catererConfirmed) {
      errors.add(new Error(ErrorCode.CATERER_CONFIRMATION_REQUIRED, null));
    }
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < lines.size(); i++) {
      Line l = lines.get(i);
      if (l.delta() != Math.rint(l.delta())) {
        errors.add(new Error(ErrorCode.DELTA_NOT_INTEGER, i));
        continue;
      }
      if (l.delta() == 0) {
        errors.add(new Error(ErrorCode.DELTA_ZERO, i));
        continue;
      }
      if (!seen.add(l.employeeCode() + "|" + l.menuItemId())) {
        errors.add(new Error(ErrorCode.DUPLICATE_LINE, i));
        continue;
      }
      Set<String> menu = menuByEmployee.get(l.employeeCode());
      if (menu == null) {
        errors.add(new Error(ErrorCode.EMPLOYEE_UNKNOWN, i));
        continue;
      }
      int current =
          effective.getOrDefault(l.employeeCode(), Map.of()).getOrDefault(l.menuItemId(), 0);
      // A meal off the day's menu can only be taken back from someone who has it.
      if (!menu.contains(l.menuItemId()) && !(current > 0 && l.delta() < 0)) {
        errors.add(new Error(ErrorCode.ITEM_NOT_ON_MENU, i));
        continue;
      }
      if (current + (int) l.delta() < 0) {
        errors.add(new Error(ErrorCode.BELOW_ZERO, i));
      }
    }
    return List.copyOf(errors);
  }

  /** BR-46: the "Caterer confirmed" tick is required when any line adds meals. */
  public static boolean addsMeals(List<Line> lines) {
    return lines.stream().anyMatch(l -> l.delta() > 0);
  }
}
