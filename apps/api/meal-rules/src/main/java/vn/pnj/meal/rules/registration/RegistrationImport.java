package vn.pnj.meal.rules.registration;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import vn.pnj.meal.rules.registration.RegistrationRules.Line;

/**
 * Excel import of a week's registrations for a unit (product owner, 2026-10-23): the admin
 * downloads the unit's grid as a file, fills in quantities and uploads it. It follows the bulk
 * grid's rules (BR-20, BR-21): only days still open for on-behalf entry, only employees of the
 * grid, whole non-negative numbers. A blank cell means no meal. One bad cell rejects the file.
 */
public final class RegistrationImport {
  public static final int MAX_QUANTITY = 99;

  /** What JavaScript's {@code Number()} accepts as a whole number in a cell, signs included. */
  private static final Pattern NUMBER =
      Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

  private RegistrationImport() {}

  /** Column key written in the template's hidden second row: {@code <date>|<menuItemId>}. */
  public static String columnKey(LocalDate date, String menuItemId) {
    return date + "|" + menuItemId;
  }

  /**
   * @param rowNumber 1-based spreadsheet row number, for error messages
   * @param cells cell text by column key
   */
  public record RawRow(int rowNumber, String employeeCode, Map<String, String> cells) {}

  public enum ErrorCode {
    /** The column's day is locked, or the dish is no longer on that day's menu. */
    COLUMN_NOT_EDITABLE,
    EMPLOYEE_NOT_IN_LIST,
    DUPLICATE_EMPLOYEE,
    QUANTITY_INVALID
  }

  /**
   * @param column column key, when the error is about one column or cell
   */
  public record RegistrationImportError(
      int rowNumber, @Nullable String column, ErrorCode code, String value) {}

  /**
   * @param lines the whole day's selection after the import (BR-20)
   */
  public record Change(String employeeCode, LocalDate date, List<Line> lines) {}

  public record Result(List<Change> changes, List<RegistrationImportError> errors) {
    public boolean ok() {
      return errors.isEmpty();
    }
  }

  private record Column(String key, LocalDate date, String itemId) {}

  /**
   * @param columns column keys in the file, in order
   * @param editable days open for on-behalf entry, with the menu items served that day
   * @param current employees of the grid with their current quantities by date and item
   */
  public static Result validate(
      List<String> columns,
      List<RawRow> rows,
      Map<LocalDate, Set<String>> editable,
      Map<String, Map<LocalDate, Map<String, Integer>>> current) {
    List<RegistrationImportError> errors = new ArrayList<>();
    List<Column> usable = new ArrayList<>();
    for (String key : columns) {
      Column column = parseKey(key);
      if (column == null
          || !editable.getOrDefault(column.date(), Set.of()).contains(column.itemId())) {
        errors.add(new RegistrationImportError(2, key, ErrorCode.COLUMN_NOT_EDITABLE, key));
      } else {
        usable.add(column);
      }
    }

    List<Change> changes = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (RawRow row : rows) {
      String code = row.employeeCode().strip();
      Map<LocalDate, Map<String, Integer>> mine = current.get(code);
      if (mine == null) {
        errors.add(
            new RegistrationImportError(
                row.rowNumber(), null, ErrorCode.EMPLOYEE_NOT_IN_LIST, code));
        continue;
      }
      if (!seen.add(code)) {
        errors.add(
            new RegistrationImportError(row.rowNumber(), null, ErrorCode.DUPLICATE_EMPLOYEE, code));
        continue;
      }
      Map<LocalDate, Map<String, Integer>> byDate = new LinkedHashMap<>();
      for (Column col : usable) {
        String text = row.cells().getOrDefault(col.key(), "").strip();
        Integer quantity = quantity(text);
        if (quantity == null) {
          errors.add(
              new RegistrationImportError(
                  row.rowNumber(), col.key(), ErrorCode.QUANTITY_INVALID, text));
          continue;
        }
        byDate
            .computeIfAbsent(col.date(), d -> new LinkedHashMap<>(mine.getOrDefault(d, Map.of())))
            .put(col.itemId(), quantity);
      }
      byDate.forEach(
          (date, quantities) -> {
            Map<String, Integer> before = mine.getOrDefault(date, Map.of());
            Set<String> items = new LinkedHashSet<>(before.keySet());
            items.addAll(quantities.keySet());
            boolean differs =
                items.stream()
                    .anyMatch(
                        id -> !before.getOrDefault(id, 0).equals(quantities.getOrDefault(id, 0)));
            if (differs) {
              changes.add(
                  new Change(
                      code,
                      date,
                      quantities.entrySet().stream()
                          .filter(e -> e.getValue() > 0)
                          .map(e -> new Line(e.getKey(), e.getValue()))
                          .toList()));
            }
          });
    }
    return errors.isEmpty()
        ? new Result(List.copyOf(changes), List.of())
        : new Result(List.of(), List.copyOf(errors));
  }

  private static Column parseKey(String key) {
    int bar = key.indexOf('|');
    if (bar < 0) {
      return null;
    }
    try {
      return new Column(
          key, LocalDate.parse(key.substring(0, bar)), key.substring(bar + 1).split("\\|")[0]);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Blank is 0; otherwise a whole number 0..99 (also written "2.0" or "2e0"), else null. */
  private static Integer quantity(String text) {
    if (text.isEmpty()) {
      return 0;
    }
    if (!NUMBER.matcher(text).matches()) {
      return null;
    }
    double value = Double.parseDouble(text);
    if (value != Math.rint(value) || value < 0 || value > MAX_QUANTITY) {
      return null;
    }
    return (int) value;
  }
}
