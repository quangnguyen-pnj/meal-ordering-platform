package vn.pnj.meal.rules.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.rules.registration.RegistrationImport.Change;
import vn.pnj.meal.rules.registration.RegistrationImport.RawRow;
import vn.pnj.meal.rules.registration.RegistrationImport.Result;
import vn.pnj.meal.rules.registration.RegistrationRules.Line;

@DisplayName("BR-21: weekly registration import")
class RegistrationImportTest {
  private static final LocalDate MON = LocalDate.of(2026, 11, 2);
  private static final LocalDate TUE = LocalDate.of(2026, 11, 3);
  private static final Map<LocalDate, Set<String>> EDITABLE =
      Map.of(MON, Set.of("mon-ca", "mon-chay"), TUE, Set.of("tue-ga"));
  private static final Map<String, Map<LocalDate, Map<String, Integer>>> CURRENT =
      Map.of("PNJP000001", Map.of(MON, Map.of("mon-ca", 1)), "PNJP000002", Map.of());
  private static final String C0 = RegistrationImport.columnKey(MON, "mon-ca");
  private static final String C1 = RegistrationImport.columnKey(MON, "mon-chay");
  private static final String C2 = RegistrationImport.columnKey(TUE, "tue-ga");
  private static final List<String> COLUMNS = List.of(C0, C1, C2);

  private static Result validate(List<String> columns, List<RawRow> rows) {
    return RegistrationImport.validate(columns, rows, EDITABLE, CURRENT);
  }

  @Test
  @DisplayName("BR-21: turns changed employee-days into whole-day selections; blank is no meal")
  void changes() {
    Result res =
        validate(
            COLUMNS,
            List.of(
                new RawRow(3, "PNJP000001", Map.of(C0, "1", C1, "", C2, "2")),
                new RawRow(4, " PNJP000002 ", Map.of(C1, "1"))));
    assertThat(res.ok()).isTrue();
    assertThat(res.changes())
        .containsExactly(
            new Change("PNJP000001", TUE, List.of(new Line("tue-ga", 2))),
            new Change("PNJP000002", MON, List.of(new Line("mon-chay", 1))));
  }

  @Test
  @DisplayName("BR-21: re-importing the downloaded file changes nothing")
  void sameFile() {
    Result res = validate(COLUMNS, List.of(new RawRow(3, "PNJP000001", Map.of(C0, "1"))));
    assertThat(res.ok()).isTrue();
    assertThat(res.changes()).isEmpty();
  }

  @Test
  @DisplayName("BR-24 / BR-20: a locked day, an unknown employee or a bad number rejects the file")
  void rejects() {
    String locked = RegistrationImport.columnKey(LocalDate.of(2026, 11, 4), "wed-thit");
    Result res =
        validate(
            List.of(C0, C1, C2, locked),
            List.of(
                new RawRow(3, "PNJP000001", Map.of(C0, "1,5")),
                new RawRow(4, "PNJP000001", Map.of()),
                new RawRow(5, "PNJP999999", Map.of()),
                new RawRow(6, "PNJP000002", Map.of(C2, "-1"))));
    assertThat(res.ok()).isFalse();
    assertThat(res.errors())
        .extracting(e -> e.rowNumber() + " " + e.code())
        .containsExactly(
            "2 COLUMN_NOT_EDITABLE",
            "3 QUANTITY_INVALID",
            "4 DUPLICATE_EMPLOYEE",
            "5 EMPLOYEE_NOT_IN_LIST",
            "6 QUANTITY_INVALID");
  }

  @Test
  @DisplayName("BR-20: whole numbers up to 99 are accepted however Excel writes them")
  void numbers() {
    Result res =
        validate(
            COLUMNS, List.of(new RawRow(3, "PNJP000002", Map.of(C0, "2.0", C1, "99", C2, "1.5"))));
    assertThat(res.errors())
        .extracting(RegistrationImport.RegistrationImportError::column)
        .containsExactly(C2);
    res = validate(COLUMNS, List.of(new RawRow(3, "PNJP000002", Map.of(C0, "100"))));
    assertThat(res.ok()).isFalse();
  }
}
