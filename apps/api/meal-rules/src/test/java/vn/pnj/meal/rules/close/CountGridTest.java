package vn.pnj.meal.rules.close;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.rules.close.CountGrid.Cell;
import vn.pnj.meal.rules.close.CountGrid.Grid;
import vn.pnj.meal.rules.close.CountGrid.Meal;

class CountGridTest {
  private static final List<Meal> MEALS =
      List.of(
          new Meal("ct", "ca", "Mặn Cá kho", 1),
          new Meal("ct", "chay", "Chay Đậu hũ", 0),
          new Meal("ct", "chao", "Cháo Gà", 2));

  private static Cell cell(String floor, int sort, String unit, String item, int q) {
    return new Cell("ct", "Nhà ăn Gò Vấp", "f" + sort, floor, sort, unit, "Tổ " + unit, item, q);
  }

  @Test
  @DisplayName("BR-44: rows are meal units by floor, columns are meals in menu order, with totals")
  void layout() {
    Grid grid =
        CountGrid.build(
            MEALS,
            List.of(
                cell("Tầng 2", 2, "B", "ca", 3),
                cell("Tầng 1", 1, "A", "ca", 2),
                cell("Tầng 1", 1, "A", "chay", 1),
                cell("Tầng 2", 2, "B", "chay", 0)),
            false);
    assertThat(grid.total()).isEqualTo(6);
    CountGrid.Block block = grid.blocks().get(0);
    assertThat(block.meals()).extracting(Meal::menuItemId).containsExactly("chay", "ca", "chao");
    assertThat(block.rows())
        .extracting(CountGrid.Row::floorName)
        .containsExactly("Tầng 1", "Tầng 2");
    assertThat(block.rows().get(0).counts()).containsExactly(1, 2, 0);
    assertThat(block.rows().get(0).total()).isEqualTo(3);
    assertThat(block.totals()).containsExactly(1, 5, 0);
    assertThat(block.total()).isEqualTo(6);
  }

  @Test
  @DisplayName("BR-46: the supplementary file keeps only the changed cells, signed")
  void onlyChanged() {
    Grid grid =
        CountGrid.build(
            MEALS,
            List.of(cell("Tầng 1", 1, "A", "ca", 2), cell("Tầng 2", 2, "B", "chao", -1)),
            true);
    CountGrid.Block block = grid.blocks().get(0);
    assertThat(block.meals()).extracting(Meal::menuItemId).containsExactly("ca", "chao");
    assertThat(block.rows()).hasSize(2);
    assertThat(block.rows().get(1).counts()).containsExactly(0, -1);
    assertThat(grid.total()).isEqualTo(1);
  }

  @Test
  @DisplayName("BR-44: meals without a meal unit get their own row instead of being dropped")
  void noMealUnit() {
    Grid grid = CountGrid.build(MEALS, List.of(cell(null, 0, null, "ca", 1)), false);
    assertThat(grid.blocks().get(0).rows().get(0).mealUnitId()).isNull();
    assertThat(grid.total()).isEqualTo(1);
  }
}
