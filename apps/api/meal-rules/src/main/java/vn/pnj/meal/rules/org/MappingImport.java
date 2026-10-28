package vn.pnj.meal.rules.org;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * BR-64: mapping import from the Excel template. One row maps an org node (floor, canteen, cost
 * center) or an employee (pick-up floor, since 2026-10-23; cost center follows the org tree). Every
 * row is validated; one invalid row rejects the whole file.
 *
 * <p>A row states the complete mapping of its target: a blank cell means "no own value", so the
 * target inherits again (BR-62). Exporting and re-importing the same file therefore changes
 * nothing. Targets not in the file are left untouched.
 */
public final class MappingImport {
  private MappingImport() {}

  public enum TargetType {
    ORG,
    EMPLOYEE
  }

  /**
   * @param rowNumber 1-based spreadsheet row number, for error messages
   */
  public record RawRow(
      int rowNumber,
      String type,
      String code,
      String floorCode,
      String canteenCode,
      String costCenterCode) {}

  /** A valid row; null codes mean "no own value". */
  public record Row(
      int rowNumber,
      TargetType type,
      String code,
      String floorCode,
      String canteenCode,
      String costCenterCode) {}

  public enum ErrorCode {
    TYPE_INVALID,
    CODE_REQUIRED,
    ORG_UNKNOWN,
    EMPLOYEE_UNKNOWN,
    FLOOR_UNKNOWN,
    CANTEEN_UNKNOWN,
    COST_CENTER_UNKNOWN,
    EMPLOYEE_CANTEEN_OR_COST_CENTER,
    DUPLICATE_TARGET
  }

  /**
   * @param field {@code type}, {@code code}, {@code floorCode}, {@code canteenCode} or {@code
   *     costCenterCode}
   */
  public record RowError(int rowNumber, String field, ErrorCode code, String value) {}

  /** Codes that exist and may be used. Inactive master data is not in these sets (BR-62). */
  public record Lookups(
      Set<String> orgCodes,
      Set<String> employeeCodes,
      Set<String> floorCodes,
      Set<String> canteenCodes,
      Set<String> costCenterCodes) {}

  /** Either every row, or every row error. */
  public record Result(List<Row> rows, List<RowError> errors) {
    public boolean ok() {
      return errors.isEmpty();
    }
  }

  private static String blankToNull(String value) {
    return value.strip().isEmpty() ? null : value.strip();
  }

  public static Result validate(List<RawRow> raw, Lookups lookups) {
    List<RowError> errors = new ArrayList<>();
    List<Row> rows = new ArrayList<>();
    Set<String> seen = new HashSet<>();

    for (RawRow r : raw) {
      int before = errors.size();
      String type = r.type().strip().toUpperCase(Locale.ROOT);
      String code = r.code().strip();
      String floorCode = blankToNull(r.floorCode());
      String canteenCode = blankToNull(r.canteenCode());
      String costCenterCode = blankToNull(r.costCenterCode());
      boolean org = type.equals("ORG");
      boolean employee = type.equals("EMPLOYEE");

      if (!org && !employee) {
        errors.add(new RowError(r.rowNumber(), "type", ErrorCode.TYPE_INVALID, r.type()));
      }
      if (code.isEmpty()) {
        errors.add(new RowError(r.rowNumber(), "code", ErrorCode.CODE_REQUIRED, r.code()));
      } else if (org && !lookups.orgCodes().contains(code)) {
        errors.add(new RowError(r.rowNumber(), "code", ErrorCode.ORG_UNKNOWN, code));
      } else if (employee && !lookups.employeeCodes().contains(code)) {
        errors.add(new RowError(r.rowNumber(), "code", ErrorCode.EMPLOYEE_UNKNOWN, code));
      }

      if (floorCode != null && !lookups.floorCodes().contains(floorCode)) {
        errors.add(new RowError(r.rowNumber(), "floorCode", ErrorCode.FLOOR_UNKNOWN, floorCode));
      }
      if (employee) {
        // BR-63: the canteen comes from the meal unit and the cost center from the org tree; only
        // the pick-up floor is set per employee.
        if (canteenCode != null) {
          errors.add(
              new RowError(
                  r.rowNumber(),
                  "canteenCode",
                  ErrorCode.EMPLOYEE_CANTEEN_OR_COST_CENTER,
                  canteenCode));
        }
        if (costCenterCode != null) {
          errors.add(
              new RowError(
                  r.rowNumber(),
                  "costCenterCode",
                  ErrorCode.EMPLOYEE_CANTEEN_OR_COST_CENTER,
                  costCenterCode));
        }
      } else {
        if (canteenCode != null && !lookups.canteenCodes().contains(canteenCode)) {
          errors.add(
              new RowError(r.rowNumber(), "canteenCode", ErrorCode.CANTEEN_UNKNOWN, canteenCode));
        }
        if (costCenterCode != null && !lookups.costCenterCodes().contains(costCenterCode)) {
          errors.add(
              new RowError(
                  r.rowNumber(), "costCenterCode", ErrorCode.COST_CENTER_UNKNOWN, costCenterCode));
        }
      }

      if (!seen.add(type + ":" + code) && !code.isEmpty()) {
        errors.add(new RowError(r.rowNumber(), "code", ErrorCode.DUPLICATE_TARGET, code));
      }

      if (errors.size() == before) {
        rows.add(
            new Row(
                r.rowNumber(),
                TargetType.valueOf(type),
                code,
                floorCode,
                canteenCode,
                costCenterCode));
      }
    }
    return errors.isEmpty()
        ? new Result(List.copyOf(rows), List.of())
        : new Result(List.of(), List.copyOf(errors));
  }
}
