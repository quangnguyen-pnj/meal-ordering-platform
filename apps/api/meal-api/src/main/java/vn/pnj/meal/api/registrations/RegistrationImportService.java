package vn.pnj.meal.api.registrations;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.common.Xlsx;
import vn.pnj.meal.api.registrations.AdminRegistrationsService.GridDayInput;
import vn.pnj.meal.api.registrations.AdminRegistrationsService.GridRowInput;
import vn.pnj.meal.api.registrations.RegistrationViews.GridCellView;
import vn.pnj.meal.api.registrations.RegistrationViews.GridDay;
import vn.pnj.meal.api.registrations.RegistrationViews.GridItem;
import vn.pnj.meal.api.registrations.RegistrationViews.GridRow;
import vn.pnj.meal.api.registrations.RegistrationViews.GridView;
import vn.pnj.meal.api.registrations.RegistrationViews.ImportView;
import vn.pnj.meal.rules.registration.RegistrationImport;
import vn.pnj.meal.rules.registration.RegistrationImport.Change;
import vn.pnj.meal.rules.registration.RegistrationImport.RawRow;
import vn.pnj.meal.rules.registration.RegistrationRules.AdminDayWindow;

/**
 * Excel import of a unit's week (product owner, 2026-10-23). The template is the bulk grid as a
 * file: one row per employee, one column per meal of every day still open for on-behalf entry,
 * filled with the current quantities. Uploading validates the whole file ({@link
 * RegistrationImport}), and applying saves every changed day in one transaction through the grid's
 * own save, so the grid's rules and audit apply unchanged (BR-20, BR-21, BR-70).
 */
@Service
public class RegistrationImportService {
  /** Row 1 is for people, row 2 (hidden) holds the column keys; data starts on row 3. */
  private static final String CODE_HEADER = "Mã NV";

  private static final String KEY_MARKER = "#";
  private static final int FIRST_DATA_ROW = 3;
  private static final int FIXED_COLUMNS = 3;
  private static final int MAX_ROWS = 2_000;
  private static final String[] WEEKDAY = {"", "T2", "T3", "T4", "T5", "T6", "T7", "CN"};

  private final AdminRegistrationsService admin;

  public RegistrationImportService(AdminRegistrationsService admin) {
    this.admin = admin;
  }

  private record Column(String key, String label, LocalDate date, String itemId) {}

  private static List<Column> columns(GridView grid) {
    List<Column> columns = new ArrayList<>();
    for (GridDay d : grid.days()) {
      if (d.window() != AdminDayWindow.ON_BEHALF) {
        continue;
      }
      String ddmm = "%02d/%02d".formatted(d.date().getDayOfMonth(), d.date().getMonthValue());
      for (GridItem i : d.items()) {
        columns.add(
            new Column(
                RegistrationImport.columnKey(d.date(), i.id()),
                WEEKDAY[d.date().getDayOfWeek().getValue()] + " " + ddmm + " · " + i.displayName(),
                d.date(),
                i.id()));
      }
    }
    return columns;
  }

  public byte[] template(
      String factoryId, String orgUnitId, LocalDate weekStart, String canteenId) {
    GridView grid = publishedGrid(factoryId, orgUnitId, weekStart, canteenId);
    List<Column> columns = columns(grid);
    XSSFWorkbook workbook = new XSSFWorkbook();
    List<String> header = new ArrayList<>(List.of(CODE_HEADER, "Họ tên", "Đơn vị"));
    columns.forEach(c -> header.add(c.label()));
    int[] widths = new int[FIXED_COLUMNS + columns.size()];
    widths[0] = 14;
    widths[1] = 28;
    widths[2] = 36;
    for (int i = FIXED_COLUMNS; i < widths.length; i++) {
      widths[i] = 18;
    }
    Sheet sheet = Xlsx.sheet(workbook, "Đăng ký", header, widths);
    CellStyle headerStyle = workbook.createCellStyle();
    Font bold = workbook.createFont();
    bold.setBold(true);
    headerStyle.setFont(bold);
    headerStyle.setWrapText(true);
    headerStyle.setVerticalAlignment(VerticalAlignment.TOP);
    sheet.getRow(0).forEach(cell -> cell.setCellStyle(headerStyle));

    List<String> keys = new ArrayList<>(List.of(KEY_MARKER, "", ""));
    columns.forEach(c -> keys.add(c.key()));
    Xlsx.addRow(sheet, keys);
    sheet.getRow(1).setZeroHeight(true);
    sheet.createFreezePane(2, 2);

    for (GridRow row : grid.rows()) {
      Row out = sheet.createRow(sheet.getLastRowNum() + 1);
      out.createCell(0).setCellValue(row.employeeCode());
      out.createCell(1).setCellValue(row.name());
      out.createCell(2).setCellValue(row.orgPath());
      for (int i = 0; i < columns.size(); i++) {
        Column c = columns.get(i);
        GridCellView cell = row.cells().get(c.date());
        int quantity = cell == null ? 0 : cell.quantities().getOrDefault(c.itemId(), 0);
        if (quantity > 0) {
          Cell target = out.createCell(FIXED_COLUMNS + i);
          target.setCellValue(quantity);
        }
      }
    }
    Sheet info = workbook.createSheet("Hướng dẫn");
    for (String line :
        List.of(
            "Đơn vị: " + grid.orgUnit().pathName(),
            "Nhà ăn: " + (grid.canteenName() == null ? "" : grid.canteenName()),
            "Tuần bắt đầu: " + weekStart,
            "Nhập số suất (số nguyên, để trống = không ăn). Không thêm, xóa hay đổi thứ tự cột.",
            "Chỉ có các ngày còn đăng ký hộ được (trước giờ khóa của ngày).")) {
      Xlsx.addRow(info, List.of(line));
    }
    return Xlsx.bytes(workbook);
  }

  private record Parsed(List<String> columns, List<RawRow> rows) {}

  public ImportView importWeek(
      String actorId,
      String factoryId,
      String orgUnitId,
      LocalDate weekStart,
      String canteenId,
      boolean apply,
      byte[] file) {
    Parsed parsed = parse(file);
    GridView grid = publishedGrid(factoryId, orgUnitId, weekStart, canteenId);
    Map<LocalDate, Set<String>> editable = new HashMap<>();
    for (GridDay d : grid.days()) {
      if (d.window() == AdminDayWindow.ON_BEHALF) {
        Set<String> ids = new HashSet<>();
        d.items().forEach(i -> ids.add(i.id()));
        editable.put(d.date(), ids);
      }
    }
    Map<String, Map<LocalDate, Map<String, Integer>>> current = new HashMap<>();
    for (GridRow r : grid.rows()) {
      Map<LocalDate, Map<String, Integer>> byDate = new HashMap<>();
      r.cells().forEach((date, cell) -> byDate.put(date, cell.quantities()));
      current.put(r.employeeCode(), byDate);
    }
    RegistrationImport.Result result =
        RegistrationImport.validate(parsed.columns(), parsed.rows(), editable, current);
    if (!result.ok()) {
      return new ImportView("REJECTED", parsed.rows().size(), result.errors(), 0, 0);
    }
    int changedEmployees =
        (int) result.changes().stream().map(Change::employeeCode).distinct().count();
    if (apply && !result.changes().isEmpty()) {
      Map<LocalDate, List<GridRowInput>> byDate = new TreeMap<>();
      for (Change c : result.changes()) {
        byDate
            .computeIfAbsent(c.date(), d -> new ArrayList<>())
            .add(new GridRowInput(c.employeeCode(), c.lines()));
      }
      admin.saveGridDays(
          actorId,
          factoryId,
          orgUnitId,
          grid.canteenId(),
          byDate.entrySet().stream().map(e -> new GridDayInput(e.getKey(), e.getValue())).toList());
    }
    return new ImportView(
        apply ? "APPLIED" : "PREVIEW",
        parsed.rows().size(),
        List.of(),
        result.changes().size(),
        changedEmployees);
  }

  private GridView publishedGrid(
      String factoryId, String orgUnitId, LocalDate weekStart, String canteenId) {
    GridView grid = admin.grid(factoryId, orgUnitId, weekStart, canteenId);
    if (!grid.state().equals("PUBLISHED") || grid.canteenId() == null) {
      throw ApiException.invalidState(
          "No published menu for this unit and week (BR-02)", Map.of("state", grid.state()));
    }
    return grid;
  }

  private static Parsed parse(byte[] file) {
    Map<Integer, List<String>> rows = new LinkedHashMap<>();
    Xlsx.readAllRows(file).forEach(r -> rows.put(r.rowNumber(), r.cells()));
    List<String> header = rows.get(1);
    List<String> keyRow = rows.get(2);
    if (header == null
        || !cell(header, 1).equals(CODE_HEADER)
        || keyRow == null
        || !cell(keyRow, 1).equals(KEY_MARKER)) {
      throw Xlsx.fileError("The file does not use the registration template", "TEMPLATE_MISMATCH");
    }
    Map<Integer, String> columns = new LinkedHashMap<>();
    for (int col = FIXED_COLUMNS + 1; col <= keyRow.size(); col++) {
      String key = cell(keyRow, col);
      if (!key.isEmpty()) {
        columns.put(col, key);
      }
    }
    List<RawRow> data = new ArrayList<>();
    rows.forEach(
        (rowNumber, cells) -> {
          if (rowNumber < FIRST_DATA_ROW) {
            return;
          }
          String employeeCode = cell(cells, 1);
          Map<String, String> values = new LinkedHashMap<>();
          columns.forEach((col, key) -> values.put(key, cell(cells, col)));
          if (employeeCode.isEmpty() && values.values().stream().allMatch(String::isEmpty)) {
            return;
          }
          data.add(new RawRow(rowNumber, employeeCode, values));
        });
    if (data.size() > MAX_ROWS) {
      throw Xlsx.fileError("Too many rows", "TOO_MANY_ROWS");
    }
    return new Parsed(List.copyOf(columns.values()), data);
  }

  private static String cell(List<String> cells, int column) {
    return column <= cells.size() ? cells.get(column - 1) : "";
  }
}
