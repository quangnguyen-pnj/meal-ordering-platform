package vn.pnj.meal.api.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Excel (.xlsx) helpers for the import templates (Apache POI). Cells are read as the text Excel
 * shows, so a code typed as a number reads "123", as ExcelJS's {@code cell.text} did.
 */
public final class Xlsx {
  public static final MediaType XLSX =
      MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private Xlsx() {}

  /** One non-empty data row of the first sheet: its 1-based number and the requested cells. */
  public record DataRow(int rowNumber, List<String> cells) {}

  /**
   * Reads the first sheet. Row 1 is the header: its first cell must equal {@code firstHeader}. Rows
   * whose requested cells are all blank are skipped.
   *
   * @param columns 1-based column numbers to read, in the order wanted
   * @param templateMessage message when the header does not match
   */
  public static List<DataRow> readFirstSheet(
      byte[] file, String firstHeader, int[] columns, String templateMessage, int maxRows) {
    try (XSSFWorkbook workbook = open(file)) {
      Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
      DataFormatter formatter = new DataFormatter();
      FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      if (sheet == null || !text(sheet.getRow(0), 1, formatter, evaluator).equals(firstHeader)) {
        throw fileError(templateMessage, "TEMPLATE_MISMATCH");
      }
      List<DataRow> rows = new ArrayList<>();
      for (Row row : sheet) {
        if (row.getRowNum() == 0) {
          continue;
        }
        List<String> cells = new ArrayList<>();
        for (int column : columns) {
          cells.add(text(row, column, formatter, evaluator));
        }
        if (cells.stream().allMatch(String::isEmpty)) {
          continue;
        }
        rows.add(new DataRow(row.getRowNum() + 1, List.copyOf(cells)));
      }
      if (rows.isEmpty()) {
        throw fileError("The file has no rows", "EMPTY_FILE");
      }
      if (rows.size() > maxRows) {
        throw fileError("Too many rows", "TOO_MANY_ROWS");
      }
      return rows;
    } catch (IOException e) {
      throw fileError("Not a readable .xlsx file", "FILE_UNREADABLE");
    }
  }

  /**
   * Every stored row of the first sheet as the text of its cells (up to its last cell), for
   * templates whose columns are not fixed. Empty when the workbook has no sheet.
   */
  public static List<DataRow> readAllRows(byte[] file) {
    try (XSSFWorkbook workbook = open(file)) {
      if (workbook.getNumberOfSheets() == 0) {
        return List.of();
      }
      DataFormatter formatter = new DataFormatter();
      FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      List<DataRow> rows = new ArrayList<>();
      for (Row row : workbook.getSheetAt(0)) {
        List<String> cells = new ArrayList<>();
        for (int column = 1; column <= Math.max(row.getLastCellNum(), 0); column++) {
          cells.add(text(row, column, formatter, evaluator));
        }
        rows.add(new DataRow(row.getRowNum() + 1, List.copyOf(cells)));
      }
      return rows;
    } catch (IOException e) {
      throw fileError("Not a readable .xlsx file", "FILE_UNREADABLE");
    }
  }

  private static XSSFWorkbook open(byte[] file) {
    try {
      return new XSSFWorkbook(new ByteArrayInputStream(file));
    } catch (IOException | RuntimeException e) {
      // POI throws a range of runtime exceptions for files that are not a valid package.
      throw fileError("Not a readable .xlsx file", "FILE_UNREADABLE");
    }
  }

  private static String text(Row row, int column, DataFormatter formatter, FormulaEvaluator eval) {
    if (row == null) {
      return "";
    }
    Cell cell = row.getCell(column - 1);
    return cell == null ? "" : formatter.formatCellValue(cell, eval).strip();
  }

  public static ApiException fileError(String message, String code) {
    return ApiException.invalid(message, List.of(Map.of("path", "file", "message", code)));
  }

  /** A sheet whose first row is a bold header; {@code widths} are in characters, as in ExcelJS. */
  public static Sheet sheet(XSSFWorkbook workbook, String name, List<String> header, int[] widths) {
    Sheet sheet = workbook.createSheet(name);
    CellStyle bold = workbook.createCellStyle();
    Font font = workbook.createFont();
    font.setBold(true);
    bold.setFont(font);
    Row row = sheet.createRow(0);
    for (int i = 0; i < header.size(); i++) {
      Cell cell = row.createCell(i);
      cell.setCellValue(header.get(i));
      cell.setCellStyle(bold);
    }
    for (int i = 0; i < widths.length; i++) {
      sheet.setColumnWidth(i, widths[i] * 256);
    }
    return sheet;
  }

  /** Appends a row of text cells; empty strings leave the cell blank. */
  public static void addRow(Sheet sheet, List<String> values) {
    Row row = sheet.createRow(sheet.getLastRowNum() + 1);
    for (int i = 0; i < values.size(); i++) {
      String value = values.get(i);
      if (value != null && !value.isEmpty()) {
        row.createCell(i).setCellValue(value);
      }
    }
  }

  public static byte[] bytes(XSSFWorkbook workbook) {
    try (workbook;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A download of {@code body} named {@code fileName}. */
  public static ResponseEntity<byte[]> download(byte[] body, String fileName) {
    return ResponseEntity.ok()
        .contentType(XLSX)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(fileName).build().toString())
        .body(body);
  }
}
