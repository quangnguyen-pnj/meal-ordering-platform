package vn.pnj.meal.api.dishes;

import java.util.List;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import vn.pnj.meal.api.common.Xlsx;
import vn.pnj.meal.rules.menu.LibraryImport;

/** The dish-library Excel template and its parser (product owner, 2026-10-23). */
@Component
public class LibraryWorkbook {
  /** Library import template: one sheet, these columns in this order; row 1 is the header. */
  public static final List<String> TEMPLATE_HEADER =
      List.of("Nhóm (Món chính / Tráng miệng)", "Mã loại món (món chính)", "Tên món");

  static final int MAX_IMPORT_ROWS = 2_000;
  static final String MAIN = "Món chính";
  static final String DESSERT = "Tráng miệng";
  static final String DISH_TYPE_SHEET = "Loại món";

  private final DishesService dishes;

  public LibraryWorkbook(DishesService dishes) {
    this.dishes = dishes;
  }

  /** An empty template with the dish-type codes on a second sheet. */
  public byte[] template(String factoryId) {
    List<DishesService.TypeCode> types = dishes.activeTypeCodes(factoryId);
    XSSFWorkbook workbook = new XSSFWorkbook();
    Sheet sheet = Xlsx.sheet(workbook, "Thư viện món", TEMPLATE_HEADER, new int[] {30, 24, 48});
    if (!types.isEmpty()) {
      Xlsx.addRow(sheet, List.of(MAIN, types.getFirst().code(), "Ví dụ: Cá kho tộ"));
    }
    Xlsx.addRow(sheet, List.of(DESSERT, "", "Ví dụ: Chuối"));

    Sheet lists = Xlsx.sheet(workbook, DISH_TYPE_SHEET, List.of("Mã loại món", "Tên"), new int[0]);
    for (DishesService.TypeCode t : types) {
      Xlsx.addRow(lists, List.of(t.code(), t.name()));
    }
    addDropdowns(sheet, types.size());
    return Xlsx.bytes(workbook);
  }

  /**
   * Turns the "Nhóm" and "Mã loại món" cells of every importable row into drop-down lists, so the
   * user picks a value instead of typing it. The type codes come from the second sheet. A blank
   * type code stays allowed because desserts have none.
   */
  private static void addDropdowns(Sheet sheet, int dishTypeCount) {
    DataValidationHelper helper = sheet.getDataValidationHelper();
    DataValidationConstraint kinds =
        helper.createExplicitListConstraint(new String[] {MAIN, DESSERT});
    DataValidation kindList =
        helper.createValidation(kinds, new CellRangeAddressList(1, MAX_IMPORT_ROWS, 0, 0));
    kindList.setEmptyCellAllowed(true);
    kindList.setShowErrorBox(true);
    kindList.createErrorBox(
        "Nhóm không hợp lệ", "Chọn \"Món chính\" hoặc \"Tráng miệng\" trong danh sách.");
    sheet.addValidationData(kindList);
    if (dishTypeCount > 0) {
      DataValidationConstraint codes =
          helper.createFormulaListConstraint(
              "'" + DISH_TYPE_SHEET + "'!$A$2:$A$" + (dishTypeCount + 1));
      DataValidation typeList =
          helper.createValidation(codes, new CellRangeAddressList(1, MAX_IMPORT_ROWS, 1, 1));
      typeList.setEmptyCellAllowed(true);
      typeList.setShowErrorBox(true);
      typeList.createErrorBox(
          "Mã loại món không hợp lệ",
          "Chọn một mã trong danh sách (xem sheet \""
              + DISH_TYPE_SHEET
              + "\"). Món tráng miệng để trống.");
      sheet.addValidationData(typeList);
    }
  }

  /** Reads the first sheet. Throws a 400 when the file is not a readable template. */
  public List<LibraryImport.RawRow> parse(byte[] file) {
    return Xlsx.readFirstSheet(
            file,
            TEMPLATE_HEADER.getFirst(),
            new int[] {1, 2, 3},
            "The file does not use the library template",
            MAX_IMPORT_ROWS)
        .stream()
        .map(
            r ->
                new LibraryImport.RawRow(
                    r.rowNumber(), r.cells().get(0), r.cells().get(1), r.cells().get(2)))
        .toList();
  }
}
