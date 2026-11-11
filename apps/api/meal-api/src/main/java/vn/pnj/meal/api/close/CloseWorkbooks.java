package vn.pnj.meal.api.close;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import vn.pnj.meal.api.common.Xlsx;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.close.CountGrid;

/**
 * BR-44: the caterer's files (Apache POI). One block per canteen: rows are meal units grouped by
 * floor, with the floor in its own column; columns are meals; a total column and a total row. The
 * final file adds a box-label sheet with one label per unit. Text is Vietnamese, as the caterer
 * reads it.
 */
public final class CloseWorkbooks {
  private CloseWorkbooks() {}

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final DateTimeFormatter SHEET_DAY = DateTimeFormatter.ofPattern("dd-MM");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
  private static final String[] WEEKDAYS = {"T2", "T3", "T4", "T5", "T6", "T7", "CN"};

  static final String FLOOR = "Tầng";
  static final String UNIT = "Đơn vị nhận cơm";
  static final String TOTAL = "Tổng";
  static final String GRAND_TOTAL = "Tổng cộng";
  static final String NO_UNIT = "Chưa có đơn vị nhận cơm";
  static final String NO_FLOOR = "Chưa có tầng";

  private record Styles(CellStyle bold, CellStyle title) {}

  private static Styles styles(XSSFWorkbook wb) {
    CellStyle bold = wb.createCellStyle();
    Font font = wb.createFont();
    font.setBold(true);
    bold.setFont(font);
    CellStyle title = wb.createCellStyle();
    Font big = wb.createFont();
    big.setBold(true);
    big.setFontHeightInPoints((short) 14);
    title.setFont(big);
    return new Styles(bold, title);
  }

  private static Row next(Sheet sheet) {
    return sheet.createRow(sheet.getPhysicalNumberOfRows() == 0 ? 0 : sheet.getLastRowNum() + 1);
  }

  private static void text(Row row, int col, String value, CellStyle style) {
    Cell cell = row.createCell(col);
    cell.setCellValue(value);
    if (style != null) {
      cell.setCellStyle(style);
    }
  }

  private static void number(Row row, int col, int value, CellStyle style, boolean blankZero) {
    if (blankZero && value == 0) {
      return;
    }
    Cell cell = row.createCell(col);
    cell.setCellValue(value);
    if (style != null) {
      cell.setCellStyle(style);
    }
  }

  /**
   * Writes the grid into the sheet below what it holds.
   *
   * @param blankZero leave zero cells empty (the supplementary file shows only changed cells)
   */
  private static void grid(Sheet sheet, CountGrid.Grid grid, Styles st, boolean blankZero) {
    int widest = 0;
    for (CountGrid.Block block : grid.blocks()) {
      text(next(sheet), 0, "Nhà ăn: " + block.canteenName(), st.bold());
      Row header = next(sheet);
      text(header, 0, FLOOR, st.bold());
      text(header, 1, UNIT, st.bold());
      for (int i = 0; i < block.meals().size(); i++) {
        text(header, 2 + i, block.meals().get(i).name(), st.bold());
      }
      text(header, 2 + block.meals().size(), TOTAL, st.bold());
      for (CountGrid.Row r : block.rows()) {
        Row row = next(sheet);
        text(row, 0, r.floorName() == null ? NO_FLOOR : r.floorName(), null);
        text(row, 1, r.mealUnitName() == null ? NO_UNIT : r.mealUnitName(), null);
        for (int i = 0; i < r.counts().size(); i++) {
          number(row, 2 + i, r.counts().get(i), null, blankZero);
        }
        number(row, 2 + r.counts().size(), r.total(), st.bold(), false);
      }
      Row totals = next(sheet);
      text(totals, 0, GRAND_TOTAL, st.bold());
      for (int i = 0; i < block.totals().size(); i++) {
        number(totals, 2 + i, block.totals().get(i), st.bold(), false);
      }
      number(totals, 2 + block.totals().size(), block.total(), st.bold(), false);
      next(sheet);
      widest = Math.max(widest, block.meals().size());
    }
    sheet.setColumnWidth(0, 16 * 256);
    sheet.setColumnWidth(1, 36 * 256);
    for (int i = 0; i <= widest; i++) {
      sheet.setColumnWidth(2 + i, 18 * 256);
    }
  }

  /** BR-44 / BR-43: the final count of day D and its box labels. */
  public static byte[] finalFile(LocalDate date, Instant finalizedAt, CountGrid.Grid grid) {
    XSSFWorkbook wb = new XSSFWorkbook();
    Styles st = styles(wb);
    Sheet sheet = wb.createSheet("Chốt số " + date.format(SHEET_DAY));
    text(next(sheet), 0, "Số suất chốt ngày " + date.format(DAY), st.title());
    text(next(sheet), 0, "Chốt lúc " + VnTime.toVn(finalizedAt).format(TIME), null);
    text(next(sheet), 0, GRAND_TOTAL + ": " + grid.total() + " suất", st.bold());
    next(sheet);
    grid(sheet, grid, st, false);
    labels(wb.createSheet("Nhãn hộp cơm"), date, grid, st);
    return Xlsx.bytes(wb);
  }

  /** BR-44: one label per meal unit, with its floor and counts, for the boxes. */
  private static void labels(Sheet sheet, LocalDate date, CountGrid.Grid grid, Styles st) {
    sheet.setColumnWidth(0, 36 * 256);
    sheet.setColumnWidth(1, 10 * 256);
    for (CountGrid.Block block : grid.blocks()) {
      for (CountGrid.Row r : block.rows()) {
        text(next(sheet), 0, r.mealUnitName() == null ? NO_UNIT : r.mealUnitName(), st.title());
        text(
            next(sheet),
            0,
            FLOOR + ": " + (r.floorName() == null ? NO_FLOOR : r.floorName()),
            st.bold());
        text(next(sheet), 0, "Nhà ăn: " + block.canteenName() + " · " + date.format(DAY), null);
        for (int i = 0; i < block.meals().size(); i++) {
          int count = r.counts().get(i);
          if (count != 0) {
            Row row = next(sheet);
            text(row, 0, block.meals().get(i).name(), null);
            number(row, 1, count, null, false);
          }
        }
        Row total = next(sheet);
        text(total, 0, TOTAL, st.bold());
        number(total, 1, r.total(), st.bold(), false);
        next(sheet);
      }
    }
  }

  /** BR-07 / BR-44: the weekly forecast, one sheet per serving day, same layout. */
  public static byte[] forecastFile(LocalDate weekStart, Map<LocalDate, CountGrid.Grid> days) {
    XSSFWorkbook wb = new XSSFWorkbook();
    Styles st = styles(wb);
    for (Map.Entry<LocalDate, CountGrid.Grid> day : days.entrySet()) {
      LocalDate date = day.getKey();
      Sheet sheet =
          wb.createSheet(
              WEEKDAYS[date.getDayOfWeek().getValue() - 1] + " " + date.format(SHEET_DAY));
      text(next(sheet), 0, "Dự trù ngày " + date.format(DAY), st.title());
      text(next(sheet), 0, GRAND_TOTAL + ": " + day.getValue().total() + " suất", st.bold());
      next(sheet);
      grid(sheet, day.getValue(), st, false);
    }
    if (days.isEmpty()) {
      text(
          next(wb.createSheet("Dự trù")),
          0,
          "Tuần " + weekStart.format(DAY) + ": không có ngày phục vụ",
          null);
    }
    return Xlsx.bytes(wb);
  }

  /**
   * BR-46: the supplementary file of one adjustment: same layout, only the changed cells, signed
   * (+2 adds two meals, -1 removes one).
   */
  public static byte[] adjustmentFile(
      LocalDate date, int number, Instant createdAt, String reason, CountGrid.Grid grid) {
    XSSFWorkbook wb = new XSSFWorkbook();
    Styles st = styles(wb);
    Sheet sheet = wb.createSheet("Điều chỉnh " + number);
    text(
        next(sheet),
        0,
        "Điều chỉnh số " + number + " sau chốt ngày " + date.format(DAY),
        st.title());
    text(next(sheet), 0, "Lúc " + VnTime.toVn(createdAt).format(TIME), null);
    text(next(sheet), 0, "Lý do: " + reason, null);
    text(
        next(sheet),
        0,
        "Chênh lệch: " + (grid.total() > 0 ? "+" : "") + grid.total() + " suất",
        st.bold());
    next(sheet);
    grid(sheet, grid, st, true);
    return Xlsx.bytes(wb);
  }
}
