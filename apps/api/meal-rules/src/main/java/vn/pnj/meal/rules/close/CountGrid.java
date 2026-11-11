package vn.pnj.meal.rules.close;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * BR-44: the layout of the caterer's files. Rows are meal units grouped by canteen and floor, with
 * the floor in its own column; columns are meals; cells are counts; with a total row and a total
 * column. One block per canteen, since each canteen has its own menu (BR-15). The final file, the
 * weekly forecast file (one sheet per day) and the supplementary file of an adjustment (only the
 * changed cells) all use it.
 */
public final class CountGrid {
  private CountGrid() {}

  private static final Collator VIETNAMESE = Collator.getInstance(Locale.forLanguageTag("vi"));

  /** A meal column of a canteen, in menu order. */
  public record Meal(String canteenId, String menuItemId, String name, int sortOrder) {}

  /**
   * One count: a meal unit (null when the employee had none), its floor and canteen, a meal and a
   * quantity (a delta for a supplementary file).
   */
  public record Cell(
      String canteenId,
      String canteenName,
      @Nullable String floorId,
      @Nullable String floorName,
      int floorSort,
      @Nullable String mealUnitId,
      @Nullable String mealUnitName,
      String menuItemId,
      int quantity) {}

  public record Row(
      @Nullable String floorName,
      @Nullable String mealUnitId,
      @Nullable String mealUnitName,
      List<Integer> counts,
      int total) {}

  public record Block(
      String canteenId,
      String canteenName,
      List<Meal> meals,
      List<Row> rows,
      List<Integer> totals,
      int total) {}

  public record Grid(List<Block> blocks, int total) {}

  private record RowKey(String floorId, String mealUnitId) {}

  /**
   * Builds the grid.
   *
   * @param meals the meal columns of every canteen, in menu order; a cell whose meal is missing
   *     gets a column at the end of its canteen
   * @param onlyChanged true for a supplementary file: meals and rows without a non-zero cell are
   *     left out
   */
  public static Grid build(List<Meal> meals, List<Cell> cells, boolean onlyChanged) {
    Map<String, List<Cell>> byCanteen = new LinkedHashMap<>();
    cells.stream()
        .sorted(Comparator.comparing(Cell::canteenName, VIETNAMESE))
        .forEach(c -> byCanteen.computeIfAbsent(c.canteenId(), k -> new ArrayList<>()).add(c));
    List<Block> blocks = new ArrayList<>();
    int grand = 0;
    for (Map.Entry<String, List<Cell>> entry : byCanteen.entrySet()) {
      String canteenId = entry.getKey();
      List<Cell> mine = entry.getValue();
      List<Meal> columns = new ArrayList<>();
      meals.stream()
          .filter(m -> m.canteenId().equals(canteenId))
          .sorted(Comparator.comparingInt(Meal::sortOrder))
          .forEach(columns::add);
      for (Cell c : mine) {
        if (columns.stream().noneMatch(m -> m.menuItemId().equals(c.menuItemId()))) {
          columns.add(new Meal(canteenId, c.menuItemId(), c.menuItemId(), Integer.MAX_VALUE));
        }
      }
      if (onlyChanged) {
        columns.removeIf(
            m ->
                mine.stream()
                    .noneMatch(c -> c.menuItemId().equals(m.menuItemId()) && c.quantity() != 0));
      }
      Map<RowKey, int[]> counts = new LinkedHashMap<>();
      Map<RowKey, Cell> firstOf = new LinkedHashMap<>();
      mine.stream()
          .sorted(
              Comparator.comparingInt(Cell::floorSort)
                  .thenComparing(c -> c.floorName() == null ? "" : c.floorName(), VIETNAMESE)
                  .thenComparing(
                      c -> c.mealUnitName() == null ? "￿" : c.mealUnitName(), VIETNAMESE))
          .forEach(
              c -> {
                RowKey key = new RowKey(c.floorId(), c.mealUnitId());
                firstOf.putIfAbsent(key, c);
                int col = indexOf(columns, c.menuItemId());
                int[] row = counts.computeIfAbsent(key, k -> new int[columns.size()]);
                if (col >= 0) {
                  row[col] += c.quantity();
                }
              });
      List<Row> rows = new ArrayList<>();
      int[] totals = new int[columns.size()];
      int blockTotal = 0;
      for (Map.Entry<RowKey, int[]> r : counts.entrySet()) {
        int[] values = r.getValue();
        boolean any = false;
        int rowTotal = 0;
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
          list.add(values[i]);
          rowTotal += values[i];
          totals[i] += values[i];
          any |= values[i] != 0;
        }
        if (!any) {
          continue;
        }
        Cell first = firstOf.get(r.getKey());
        rows.add(
            new Row(
                first.floorName(),
                first.mealUnitId(),
                first.mealUnitName(),
                List.copyOf(list),
                rowTotal));
        blockTotal += rowTotal;
      }
      if (rows.isEmpty() && onlyChanged) {
        continue;
      }
      List<Integer> totalList = new ArrayList<>();
      for (int t : totals) {
        totalList.add(t);
      }
      blocks.add(
          new Block(
              canteenId,
              mine.get(0).canteenName(),
              List.copyOf(columns),
              List.copyOf(rows),
              List.copyOf(totalList),
              blockTotal));
      grand += blockTotal;
    }
    return new Grid(List.copyOf(blocks), grand);
  }

  private static int indexOf(List<Meal> columns, String menuItemId) {
    for (int i = 0; i < columns.size(); i++) {
      if (columns.get(i).menuItemId().equals(menuItemId)) {
        return i;
      }
    }
    return -1;
  }
}
