package vn.pnj.meal.rules.org;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.rules.org.DmpTree.ErrorCode;
import vn.pnj.meal.rules.org.MappingImport.RawRow;
import vn.pnj.meal.rules.org.MappingImport.Result;
import vn.pnj.meal.rules.org.MappingImport.Row;
import vn.pnj.meal.rules.org.MappingImport.RowError;
import vn.pnj.meal.rules.org.MappingImport.TargetType;

class OrgImportTest {
  @Nested
  @DisplayName("BR-61: DMP levels map to 1–6")
  class Levels {
    private DmpTree.Input n(String code, String parentCode) {
      return new DmpTree.Input(code, code, parentCode, "L " + code, null);
    }

    @Test
    @DisplayName("BR-61: depth 1 to 6 maps to the same level, anything else does not")
    void depth() {
      assertThat(IntStream.rangeClosed(1, 6).mapToObj(DmpTree::mapDmpLevel))
          .containsExactly(1, 2, 3, 4, 5, 6);
      assertThat(DmpTree.mapDmpLevel(0)).isNull();
      assertThat(DmpTree.mapDmpLevel(7)).isNull();
    }

    @Test
    @DisplayName("BR-61: computes levels from the tree and orders parents first")
    void order() {
      DmpTree.Result r = DmpTree.build(List.of(n("C", "B"), n("A", null), n("B", "A")));
      assertThat(r.nodes()).extracting(x -> x.code() + x.level()).containsExactly("A1", "B2", "C3");
    }

    @Test
    @DisplayName("BR-61: a 7th level rejects the whole tree")
    void tooDeep() {
      List<String> codes = List.of("L1", "L2", "L3", "L4", "L5", "L6", "L7");
      DmpTree.Result r =
          DmpTree.build(
              IntStream.range(0, codes.size())
                  .mapToObj(i -> n(codes.get(i), i == 0 ? null : codes.get(i - 1)))
                  .toList());
      assertThat(r.ok()).isFalse();
      assertThat(r.errors()).containsExactly(new DmpTree.Error(ErrorCode.TOO_DEEP, "L7", null, 7));
    }

    @Test
    @DisplayName("BR-61: unknown parents, duplicates and cycles reject the tree")
    void broken() {
      assertThat(DmpTree.build(List.of(n("A", "X"))).errors())
          .extracting(DmpTree.Error::code)
          .containsExactly(ErrorCode.UNKNOWN_PARENT);
      assertThat(DmpTree.build(List.of(n("A", null), n("A", null))).errors())
          .extracting(DmpTree.Error::code)
          .containsExactly(ErrorCode.DUPLICATE_CODE);
      assertThat(DmpTree.build(List.of(n("A", "B"), n("B", "A"))).errors())
          .extracting(DmpTree.Error::code)
          .containsExactly(ErrorCode.CYCLE, ErrorCode.CYCLE);
    }
  }

  @Nested
  @DisplayName("BR-64: mapping import validation")
  class Mapping {
    private final MappingImport.Lookups lookups =
        new MappingImport.Lookups(
            Set.of("XCT", "KVP"),
            Set.of("PNJP000001"),
            Set.of("T1", "T2"),
            Set.of("CT-GV"),
            Set.of("CC01", "CC03"));

    private RawRow row(int n, String type, String code, String floor, String canteen, String cc) {
      return new RawRow(n, type, code, floor, canteen, cc);
    }

    @Test
    @DisplayName("BR-64: valid rows parse, blanks mean \"inherit\"")
    void valid() {
      Result r =
          MappingImport.validate(
              List.of(
                  row(2, "ORG", "XCT", "T2", "", "CC03"),
                  row(3, "employee", "PNJP000001", "T1", "", "")),
              lookups);
      assertThat(r.ok()).isTrue();
      assertThat(r.rows())
          .containsExactly(
              new Row(2, TargetType.ORG, "XCT", "T2", null, "CC03"),
              new Row(3, TargetType.EMPLOYEE, "PNJP000001", "T1", null, null));
    }

    @Test
    @DisplayName("BR-64: one invalid row rejects the whole file with row-level errors")
    void oneBadRow() {
      Result r =
          MappingImport.validate(
              List.of(
                  row(2, "ORG", "XCT", "T2", "", ""),
                  row(3, "ORG", "KVP", "T9", "", ""),
                  row(4, "ORG", "NOPE", "", "", "")),
              lookups);
      assertThat(r.ok()).isFalse();
      assertThat(r.rows()).isEmpty();
      assertThat(r.errors())
          .containsExactly(
              new RowError(3, "floorCode", MappingImport.ErrorCode.FLOOR_UNKNOWN, "T9"),
              new RowError(4, "code", MappingImport.ErrorCode.ORG_UNKNOWN, "NOPE"));
    }

    @Test
    @DisplayName(
        "BR-64 / BR-63: an employee row sets only the pick-up floor, never canteen or cost center")
    void employeeRow() {
      Result r =
          MappingImport.validate(
              List.of(row(2, "EMPLOYEE", "PNJP000001", "T1", "CT-GV", "CC01")), lookups);
      assertThat(r.errors())
          .extracting(e -> e.field() + ":" + e.code())
          .containsExactly(
              "canteenCode:EMPLOYEE_CANTEEN_OR_COST_CENTER",
              "costCenterCode:EMPLOYEE_CANTEEN_OR_COST_CENTER");
    }

    @Test
    @DisplayName("BR-64: rejects bad types, missing codes, unknown cost centers and duplicates")
    void rejects() {
      Result r =
          MappingImport.validate(
              List.of(
                  row(2, "X", "XCT", "", "", ""),
                  row(3, "ORG", "", "", "", ""),
                  row(4, "ORG", "XCT", "", "", "CC99"),
                  row(5, "ORG", "KVP", "", "", ""),
                  row(6, "ORG", "KVP", "", "", "")),
              lookups);
      assertThat(r.errors())
          .extracting(e -> e.rowNumber() + ":" + e.code())
          .containsExactly(
              "2:TYPE_INVALID", "3:CODE_REQUIRED", "4:COST_CENTER_UNKNOWN", "6:DUPLICATE_TARGET");
    }
  }
}
