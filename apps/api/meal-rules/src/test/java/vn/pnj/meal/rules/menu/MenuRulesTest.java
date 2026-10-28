package vn.pnj.meal.rules.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.rules.menu.MenuRules.Add;
import vn.pnj.meal.rules.menu.MenuRules.DayCounts;
import vn.pnj.meal.rules.menu.MenuRules.DayGap;
import vn.pnj.meal.rules.menu.MenuRules.DayGaps;
import vn.pnj.meal.rules.menu.MenuRules.DesiredItem;
import vn.pnj.meal.rules.menu.MenuRules.EditError;
import vn.pnj.meal.rules.menu.MenuRules.EditErrorCode;
import vn.pnj.meal.rules.menu.MenuRules.EditResult;
import vn.pnj.meal.rules.menu.MenuRules.ItemRef;
import vn.pnj.meal.rules.menu.MenuRules.Keep;
import vn.pnj.meal.rules.menu.MenuRules.MixCheck;
import vn.pnj.meal.rules.menu.MenuRules.MixIssue;
import vn.pnj.meal.rules.menu.MenuRules.MixRule;
import vn.pnj.meal.rules.menu.MenuRules.Remove;
import vn.pnj.meal.rules.menu.MenuRules.Replace;

class MenuRulesTest {
  @Test
  @DisplayName("BR-12: is the type in brackets, then the dish name")
  void displayName() {
    assertThat(MenuRules.mealDisplayName("Chay", "Gạo lứt muối mè"))
        .isEqualTo("[Chay] Gạo lứt muối mè");
    assertThat(MenuRules.mealDisplayName("Mặn", "Cá kho tộ")).isEqualTo("[Mặn] Cá kho tộ");
    assertThat(MenuRules.mealDisplayName("Eat clean", " Ức gà áp chảo "))
        .isEqualTo("[Eat clean] Ức gà áp chảo");
  }

  @Test
  @DisplayName("BR-10: names are trimmed with single spaces and compared without case")
  void libraryNames() {
    assertThat(MenuRules.normalizeLibraryName("  Cá   kho  tộ ")).isEqualTo("Cá kho tộ");
    assertThat(MenuRules.normalizeLibraryName(" Cá\tkho ")).isEqualTo("Cá kho");
    assertThat(MenuRules.libraryNameKey("CÁ KHO TỘ"))
        .isEqualTo(MenuRules.libraryNameKey(" cá kho  tộ"));
    assertThat(MenuRules.libraryNameKey("Cá kho"))
        .isNotEqualTo(MenuRules.libraryNameKey("Cá kho tộ"));
  }

  @Test
  @DisplayName("BR-10: a name typed with combining accents is the same dish (NFC)")
  void nfc() {
    String decomposed = java.text.Normalizer.normalize("Cá kho", java.text.Normalizer.Form.NFD);
    assertThat(MenuRules.libraryNameKey(decomposed)).isEqualTo(MenuRules.libraryNameKey("cá kho"));
  }

  @Nested
  @DisplayName("BR-13: usual menu mix")
  class Mix {
    private final List<MixRule> rules =
        List.of(
            new MixRule("chay", "Chay", 1, 1),
            new MixRule("chao", "Cháo", 1, 1),
            new MixRule("man", "Mặn", 2, 3),
            new MixRule("clean", "Eat clean", null, null));

    @Test
    @DisplayName("BR-13: 1 Chay + 1 Cháo + 2 or 3 Mặn is the usual mix")
    void usual() {
      assertThat(MenuRules.checkMenuMix(List.of("chay", "chao", "man", "man"), rules).usual())
          .isTrue();
      assertThat(
              MenuRules.checkMenuMix(List.of("man", "chay", "man", "chao", "man"), rules).usual())
          .isTrue();
    }

    @Test
    @DisplayName("BR-13: a different mix reports what is off")
    void unusual() {
      MixCheck check = MenuRules.checkMenuMix(List.of("man", "man", "man", "man", "chay"), rules);
      assertThat(check.usual()).isFalse();
      assertThat(check.issues())
          .containsExactly(
              new MixIssue("chao", "Cháo", 0, 1, 1), new MixIssue("man", "Mặn", 4, 2, 3));
      assertThat(check.counts())
          .containsExactly(java.util.Map.entry("man", 4), java.util.Map.entry("chay", 1));
    }

    @Test
    @DisplayName("BR-13: a type without bounds is never an issue, and an empty day is unusual")
    void unbounded() {
      assertThat(
              MenuRules.checkMenuMix(List.of("chay", "chao", "man", "man", "clean", "clean"), rules)
                  .usual())
          .isTrue();
      assertThat(MenuRules.checkMenuMix(List.of(), rules).issues()).hasSize(3);
    }

    @Test
    @DisplayName("BR-13: only one bound can be set")
    void oneBound() {
      List<MixRule> atMostOne = List.of(new MixRule("clean", "Eat clean", null, 1));
      assertThat(MenuRules.checkMenuMix(List.of(), atMostOne).usual()).isTrue();
      assertThat(
              MenuRules.checkMenuMix(List.of("clean", "clean"), atMostOne).issues().get(0).actual())
          .isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("publishing needs a main dish and a dessert on every serving day")
  class Gaps {
    @Test
    @DisplayName("a serving day lacking either is reported; a day off never is")
    void day() {
      assertThat(MenuRules.menuDayGaps(true, 2, 1)).isEmpty();
      assertThat(MenuRules.menuDayGaps(true, 0, 0)).containsExactly(DayGap.MAIN, DayGap.DESSERT);
      assertThat(MenuRules.menuDayGaps(true, 3, 0)).containsExactly(DayGap.DESSERT);
      assertThat(MenuRules.menuDayGaps(false, 0, 0)).isEmpty();
    }

    @Test
    @DisplayName("lists only the days that block publishing")
    void week() {
      assertThat(
              MenuRules.menuWeekGaps(
                  List.of(
                      new DayCounts(LocalDate.of(2026, 11, 2), true, 4, 1),
                      new DayCounts(LocalDate.of(2026, 11, 3), true, 0, 1),
                      new DayCounts(LocalDate.of(2026, 11, 7), false, 0, 0))))
          .containsExactly(new DayGaps(LocalDate.of(2026, 11, 3), List.of(DayGap.MAIN)));
    }
  }

  @Nested
  @DisplayName("BR-08: serving days")
  class ServingDays {
    private final List<Integer> defaults = List.of(1, 2, 3, 4, 5);

    @Test
    @DisplayName("BR-08: a week offers Monday to Sunday, serving Monday to Friday by default")
    void week() {
      List<LocalDate> dates = MenuRules.menuWeekDates(LocalDate.of(2026, 11, 2));
      assertThat(dates)
          .containsExactly(
              LocalDate.of(2026, 11, 2),
              LocalDate.of(2026, 11, 3),
              LocalDate.of(2026, 11, 4),
              LocalDate.of(2026, 11, 5),
              LocalDate.of(2026, 11, 6),
              LocalDate.of(2026, 11, 7),
              LocalDate.of(2026, 11, 8));
      assertThat(dates.stream().map(d -> MenuRules.isDefaultServingDay(d, defaults)))
          .containsExactly(true, true, true, true, true, false, false);
    }

    @Test
    @DisplayName("BR-08: Sunday is offered even when the servingDays setting leaves it out")
    void sunday() {
      assertThat(MenuRules.menuWeekDates(LocalDate.of(2026, 11, 2)).getLast())
          .isEqualTo(LocalDate.of(2026, 11, 8));
      assertThat(
              MenuRules.isDefaultServingDay(
                  LocalDate.of(2026, 11, 8), List.of(1, 2, 3, 4, 5, 6, 7)))
          .isTrue();
    }

    @Test
    @DisplayName("BR-08: weekStart must be a Monday")
    void monday() {
      assertThatThrownBy(() -> MenuRules.menuWeekDates(LocalDate.of(2026, 11, 3)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("BR-11 / BR-14: planning a day edit")
  class DayEdit {
    private final List<ItemRef> existing =
        List.of(new ItemRef("i1", "d1"), new ItemRef("i2", "d2"), new ItemRef("i3", "d3"));

    @Test
    @DisplayName("BR-11: keeps items, reorders them and adds new dishes")
    void reorder() {
      EditResult r =
          MenuRules.planMenuDayEdit(
              existing,
              List.of(
                  new DesiredItem("i2", "d2"),
                  new DesiredItem("i1", "d1"),
                  new DesiredItem("i3", "d3"),
                  new DesiredItem(null, "d4")));
      assertThat(r.ok()).isTrue();
      assertThat(r.plan().keep())
          .containsExactly(new Keep("i2", 0), new Keep("i1", 1), new Keep("i3", 2));
      assertThat(r.plan().replace()).isEmpty();
      assertThat(r.plan().remove()).isEmpty();
      assertThat(r.plan().add()).containsExactly(new Add("d4", 3));
    }

    @Test
    @DisplayName(
        "BR-14: an existing item with another dish is a replacement; a missing item is a removal")
    void replaceAndRemove() {
      EditResult r =
          MenuRules.planMenuDayEdit(
              existing, List.of(new DesiredItem("i1", "d9"), new DesiredItem("i2", "d2")));
      assertThat(r.plan().replace()).containsExactly(new Replace("i1", "d1", "d9", 0));
      assertThat(r.plan().remove()).containsExactly(new Remove("i3", "d3"));
    }

    @Test
    @DisplayName("BR-14: rejects unknown items and the same dish twice on a day")
    void errors() {
      assertThat(
              MenuRules.planMenuDayEdit(existing, List.of(new DesiredItem("nope", "d1"))).errors())
          .containsExactly(new EditError(EditErrorCode.UNKNOWN_ITEM, "nope"));
      assertThat(
              MenuRules.planMenuDayEdit(
                      existing, List.of(new DesiredItem("i1", "d2"), new DesiredItem("i2", "d2")))
                  .errors())
          .containsExactly(new EditError(EditErrorCode.DUPLICATE_DISH, "d2"));
    }
  }
}
