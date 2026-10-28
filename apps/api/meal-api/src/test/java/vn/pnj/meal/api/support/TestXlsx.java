package vn.pnj.meal.api.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Builds and reads small .xlsx files in tests, as a user's spreadsheet would hold them. */
public final class TestXlsx {
  private TestXlsx() {}

  /** One sheet: the header row, then the rows (null or "" leaves a cell blank). */
  public static byte[] build(List<String> header, List<List<String>> rows) throws IOException {
    try (XSSFWorkbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("Sheet1");
      List<List<String>> all = new ArrayList<>();
      all.add(header);
      all.addAll(rows);
      for (int r = 0; r < all.size(); r++) {
        Row row = sheet.createRow(r);
        List<String> cells = all.get(r);
        for (int c = 0; c < cells.size(); c++) {
          if (cells.get(c) != null && !cells.get(c).isEmpty()) {
            row.createCell(c).setCellValue(cells.get(c));
          }
        }
      }
      wb.write(out);
      return out.toByteArray();
    }
  }

  /** The text of the given 1-based columns of every row of a sheet, header included. */
  public static List<List<String>> read(byte[] file, int sheetIndex, int... columns)
      throws IOException {
    try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
      DataFormatter formatter = new DataFormatter();
      List<List<String>> rows = new ArrayList<>();
      for (Row row : wb.getSheetAt(sheetIndex)) {
        List<String> cells = new ArrayList<>();
        for (int column : columns) {
          Cell cell = row.getCell(column - 1);
          cells.add(cell == null ? "" : formatter.formatCellValue(cell));
        }
        rows.add(cells);
      }
      return rows;
    }
  }

  public static String sheetName(byte[] file, int sheetIndex) throws IOException {
    try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
      return wb.getSheetName(sheetIndex);
    }
  }
}
