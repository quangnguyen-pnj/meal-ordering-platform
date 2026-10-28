package vn.pnj.meal.rules.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.rules.menu.LibraryImport.Kind;
import vn.pnj.meal.rules.menu.LibraryImport.RawRow;
import vn.pnj.meal.rules.menu.LibraryImport.Result;

@DisplayName("BR-10: dish library import")
class LibraryImportTest {
  private static final LibraryImport.Lookups LOOKUPS =
      new LibraryImport.Lookups(
          Set.of("MAN", "CHAY"),
          Set.of(LibraryImport.dishKey("MAN", "Cá kho tộ")),
          Set.of(MenuRules.libraryNameKey("Chuối")));

  private static RawRow row(int n, String kind, String typeCode, String name) {
    return new RawRow(n, kind, typeCode, name);
  }

  @Test
  @DisplayName("BR-10: adds new dishes and desserts and skips what the library already has")
  void skipsExisting() {
    Result res =
        LibraryImport.validate(
            List.of(
                row(2, "Món chính", "man", " Cá   kho tộ "),
                row(3, "MÓN CHÍNH", "CHAY", "Đậu hũ sốt cà"),
                row(4, "Tráng miệng", "", "chuối"),
                row(5, "Tráng miệng", "MAN", "Rau câu")),
            LOOKUPS);
    assertThat(res.ok()).isTrue();
    assertThat(res.toCreate())
        .extracting(r -> List.of(r.kind(), String.valueOf(r.typeCode()), r.name()))
        .containsExactly(
            List.of(Kind.MAIN, "CHAY", "Đậu hũ sốt cà"), List.of(Kind.DESSERT, "null", "Rau câu"));
    assertThat(res.alreadyInLibrary())
        .extracting(LibraryImport.Row::rowNumber)
        .containsExactly(2, 4);
  }

  @Test
  @DisplayName("BR-10: one bad row rejects the file, with every error listed")
  void rejects() {
    Result res =
        LibraryImport.validate(
            List.of(
                row(2, "Món phụ", "MAN", "X"),
                row(3, "Món chính", "", "Gà"),
                row(4, "Món chính", "BBQ", "Bò"),
                row(5, "Món chính", "MAN", ""),
                row(6, "Món chính", "MAN", "Gà chiên"),
                row(7, "Món chính", "MAN", "gà  chiên")),
            LOOKUPS);
    assertThat(res.ok()).isFalse();
    assertThat(res.errors())
        .extracting(e -> e.rowNumber() + ":" + e.code())
        .containsExactly(
            "2:KIND_INVALID",
            "3:TYPE_REQUIRED",
            "4:TYPE_UNKNOWN",
            "5:NAME_REQUIRED",
            "7:DUPLICATE_IN_FILE");
  }

  @Test
  @DisplayName("BR-10: the same name with another type is another dish")
  void otherType() {
    assertThat(
            LibraryImport.validate(List.of(row(2, "Món chính", "CHAY", "Cá kho tộ")), LOOKUPS)
                .toCreate())
        .hasSize(1);
  }
}
