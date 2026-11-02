package vn.pnj.meal.api.dishes;

import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Row;
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

  /**
   * The dish-type lookup sits in columns E–F of the same sheet, after an empty column D. The parser
   * reads only A–C, so the lookup is never imported.
   */
  static final int LOOKUP_FIRST_COLUMN = 4;

  static final List<String> LOOKUP_HEADER = List.of("Bảng tra: mã loại món", "Tên loại món");

  private final DishesService dishes;

  public LibraryWorkbook(DishesService dishes) {
    this.dishes = dishes;
  }

  /**
   * An empty template with the active dish-type codes as a lookup on the right. The "Mã loại món"
   * column is free text, not a drop-down (product owner, 2026-11-02).
   */
  public byte[] template(String factoryId) {
    List<DishesService.TypeCode> types = dishes.activeTypeCodes(factoryId);
    XSSFWorkbook workbook = new XSSFWorkbook();
    Sheet sheet =
        Xlsx.sheet(workbook, "Thư viện món", TEMPLATE_HEADER, new int[] {30, 24, 48, 10, 24, 28});
    if (!types.isEmpty()) {
      Xlsx.addRow(sheet, List.of(MAIN, types.getFirst().code(), "Ví dụ: Cá kho tộ"));
    }
    Xlsx.addRow(sheet, List.of(DESSERT, "", "Ví dụ: Chuối"));
    addDishTypeLookup(sheet, types);
    addKindDropdown(sheet);
    return Xlsx.bytes(workbook);
  }

  /** Writes the lookup header next to the template header, then one type per row below it. */
  private static void addDishTypeLookup(Sheet sheet, List<DishesService.TypeCode> types) {
    Row headerRow = sheet.getRow(0);
    CellStyle headerStyle = headerRow.getCell(0).getCellStyle();
    for (int i = 0; i < LOOKUP_HEADER.size(); i++) {
      Cell cell = headerRow.createCell(LOOKUP_FIRST_COLUMN + i);
      cell.setCellValue(LOOKUP_HEADER.get(i));
      cell.setCellStyle(headerStyle);
    }
    for (int i = 0; i < types.size(); i++) {
      int rowIndex = i + 1;
      Row existingRow = sheet.getRow(rowIndex);
      Row row = existingRow != null ? existingRow : sheet.createRow(rowIndex);
      row.createCell(LOOKUP_FIRST_COLUMN).setCellValue(types.get(i).code());
      row.createCell(LOOKUP_FIRST_COLUMN + 1).setCellValue(types.get(i).name());
    }
  }

  /** Turns the "Nhóm" cell of every importable row into a drop-down of the two groups. */
  private static void addKindDropdown(Sheet sheet) {
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
