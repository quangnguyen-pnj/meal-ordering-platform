package vn.pnj.meal.rules.menu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Excel import of one canteen's dish library (product owner, 2026-10-23). A row is a main dish
 * (with a dish-type code) or a dessert (no type). Every row is validated; one invalid row rejects
 * the file. Rows already in the library are skipped, so importing the same file twice adds nothing
 * (the "added twice" bug).
 */
public final class LibraryImport {
  private static final Locale VI = Locale.forLanguageTag("vi");
  private static final int MAX_NAME_LENGTH = 120;

  private LibraryImport() {}

  public enum Kind {
    MAIN,
    DESSERT
  }

  /** Accepted spellings of the "Nhóm" column, compared without case. */
  private static final Map<String, Kind> KIND_ALIASES =
      Map.of(
          "món chính", Kind.MAIN,
          "main", Kind.MAIN,
          "tráng miệng", Kind.DESSERT,
          "dessert", Kind.DESSERT);

  /**
   * @param rowNumber 1-based spreadsheet row number, for error messages
   */
  public record RawRow(int rowNumber, String kind, String typeCode, String name) {}

  /**
   * @param typeCode main dishes only
   */
  public record Row(int rowNumber, Kind kind, String typeCode, String name) {}

  public enum ErrorCode {
    KIND_INVALID,
    NAME_REQUIRED,
    NAME_TOO_LONG,
    TYPE_REQUIRED,
    TYPE_UNKNOWN,
    DUPLICATE_IN_FILE
  }

  /**
   * @param field {@code kind}, {@code typeCode} or {@code name}
   */
  public record RowError(int rowNumber, String field, ErrorCode code, String value) {}

  /**
   * @param typeCodes codes of active dish types
   * @param existingDishes {@link #dishKey} of every main dish already in the canteen's library
   * @param existingDesserts {@link MenuRules#libraryNameKey} of every dessert already there
   */
  public record Lookups(
      Set<String> typeCodes, Set<String> existingDishes, Set<String> existingDesserts) {}

  /** Either the rows to add and the rows already there, or every row error. */
  public record Result(List<Row> toCreate, List<Row> alreadyInLibrary, List<RowError> errors) {
    public boolean ok() {
      return errors.isEmpty();
    }
  }

  public static String dishKey(String typeCode, String name) {
    return typeCode + "|" + MenuRules.libraryNameKey(name);
  }

  public static Result validate(List<RawRow> raw, Lookups lookups) {
    List<RowError> errors = new ArrayList<>();
    List<Row> rows = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (RawRow r : raw) {
      Kind kind = KIND_ALIASES.get(r.kind().strip().toLowerCase(VI));
      String name = MenuRules.normalizeLibraryName(r.name());
      String typeCode = r.typeCode().strip().toUpperCase(Locale.ROOT);
      int before = errors.size();
      if (kind == null) {
        errors.add(new RowError(r.rowNumber(), "kind", ErrorCode.KIND_INVALID, r.kind()));
      }
      if (name.isEmpty()) {
        errors.add(new RowError(r.rowNumber(), "name", ErrorCode.NAME_REQUIRED, r.name()));
      } else if (name.length() > MAX_NAME_LENGTH) {
        errors.add(new RowError(r.rowNumber(), "name", ErrorCode.NAME_TOO_LONG, r.name()));
      }
      if (kind == Kind.MAIN) {
        if (typeCode.isEmpty()) {
          errors.add(new RowError(r.rowNumber(), "typeCode", ErrorCode.TYPE_REQUIRED, ""));
        } else if (!lookups.typeCodes().contains(typeCode)) {
          errors.add(new RowError(r.rowNumber(), "typeCode", ErrorCode.TYPE_UNKNOWN, r.typeCode()));
        }
      }
      if (errors.size() > before || kind == null) {
        continue;
      }
      String key =
          kind == Kind.MAIN
              ? "M:" + dishKey(typeCode, name)
              : "D:" + MenuRules.libraryNameKey(name);
      if (!seen.add(key)) {
        errors.add(new RowError(r.rowNumber(), "name", ErrorCode.DUPLICATE_IN_FILE, r.name()));
        continue;
      }
      rows.add(new Row(r.rowNumber(), kind, kind == Kind.MAIN ? typeCode : null, name));
    }
    if (!errors.isEmpty()) {
      return new Result(List.of(), List.of(), List.copyOf(errors));
    }
    List<Row> toCreate = new ArrayList<>();
    List<Row> already = new ArrayList<>();
    for (Row row : rows) {
      boolean exists =
          row.kind() == Kind.MAIN
              ? lookups.existingDishes().contains(dishKey(row.typeCode(), row.name()))
              : lookups.existingDesserts().contains(MenuRules.libraryNameKey(row.name()));
      (exists ? already : toCreate).add(row);
    }
    return new Result(List.copyOf(toCreate), List.copyOf(already), List.of());
  }
}
