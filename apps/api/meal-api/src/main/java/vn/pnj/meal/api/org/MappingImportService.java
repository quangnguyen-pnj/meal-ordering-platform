package vn.pnj.meal.api.org;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.common.Xlsx;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.db.SqlTime;
import vn.pnj.meal.rules.org.MappingImport;
import vn.pnj.meal.rules.org.MappingImport.Row;
import vn.pnj.meal.rules.org.MappingImport.RowError;
import vn.pnj.meal.rules.org.MappingImport.TargetType;

/** BR-64: mapping export and the validated, all-or-nothing import. */
@Service
public class MappingImportService {
  /** BR-64 template: one sheet, these columns in this order; row 1 is the header. */
  public static final List<String> TEMPLATE_HEADER =
      List.of(
          "Loại (ORG/EMPLOYEE)",
          "Mã đơn vị / mã nhân viên",
          "Tên (chỉ để đọc)",
          "Mã tầng",
          "Mã nhà ăn",
          "Mã trung tâm chi phí");

  private static final String SHEET_NAME = "Mapping";
  private static final int MAX_ROWS = 20_000;

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final AuditService audit;
  private final ClockService clock;

  public MappingImportService(
      JdbcClient jdbc, JsonMapper json, AuditService audit, ClockService clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.audit = audit;
    this.clock = clock;
  }

  private record UnitExport(
      String dmpCode, String name, int level, String floor, String canteen, String costCenter) {}

  private record EmployeeExport(String employeeCode, String name, String floor) {}

  private record ListEntry(String code, String name, boolean active) {}

  /** BR-64: export uses the same template as import, filled with the current own mapping. */
  @Transactional(readOnly = true)
  public byte[] exportWorkbook(String factoryId) {
    List<UnitExport> units =
        jdbc.sql(
                """
                SELECT o."dmpCode", o.name, o.level, f.code AS floor, c.code AS canteen,
                       cc.code AS "costCenter"
                FROM "OrgUnit" o
                LEFT JOIN "Floor" f ON f.id = o."floorId"
                LEFT JOIN "Canteen" c ON c.id = o."canteenId"
                LEFT JOIN "CostCenter" cc ON cc.id = o."costCenterId"
                WHERE o."factoryId" = :factoryId ORDER BY o.path
                """)
            .param("factoryId", factoryId)
            .query(UnitExport.class)
            .list();
    List<EmployeeExport> employees =
        jdbc.sql(
                """
                SELECT e."employeeCode", e.name, f.code AS floor
                FROM "Employee" e JOIN "OrgUnit" o ON o.id = e."orgUnitId"
                JOIN "Floor" f ON f.id = e."floorOverrideId"
                WHERE o."factoryId" = :factoryId ORDER BY e."employeeCode"
                """)
            .param("factoryId", factoryId)
            .query(EmployeeExport.class)
            .list();

    XSSFWorkbook workbook = new XSSFWorkbook();
    Sheet sheet =
        Xlsx.sheet(workbook, SHEET_NAME, TEMPLATE_HEADER, new int[] {20, 26, 40, 12, 14, 24});
    for (UnitExport u : units) {
      Xlsx.addRow(
          sheet,
          List.of(
              "ORG",
              u.dmpCode(),
              "  ".repeat(Math.max(0, u.level() - 1)) + u.name(),
              Objects.toString(u.floor(), ""),
              Objects.toString(u.canteen(), ""),
              Objects.toString(u.costCenter(), "")));
    }
    for (EmployeeExport e : employees) {
      Xlsx.addRow(sheet, List.of("EMPLOYEE", e.employeeCode(), e.name(), e.floor(), "", ""));
    }

    Sheet lists =
        Xlsx.sheet(workbook, "Danh mục", List.of("Loại", "Mã", "Tên", "Đang dùng"), new int[0]);
    addList(
        lists,
        "Tầng",
        "SELECT code, name, active FROM \"Floor\" WHERE \"factoryId\" = :f ORDER BY \"sortOrder\"",
        factoryId);
    addList(
        lists,
        "Nhà ăn",
        "SELECT code, name, active FROM \"Canteen\" WHERE \"factoryId\" = :f ORDER BY code",
        factoryId);
    addList(
        lists,
        "Trung tâm chi phí",
        "SELECT code, name, active FROM \"CostCenter\" WHERE \"factoryId\" = :f ORDER BY code",
        factoryId);
    return Xlsx.bytes(workbook);
  }

  private void addList(Sheet sheet, String kind, String sql, String factoryId) {
    for (ListEntry e : jdbc.sql(sql).param("f", factoryId).query(ListEntry.class).list()) {
      Xlsx.addRow(sheet, List.of(kind, e.code(), e.name(), e.active() ? "Có" : "Không"));
    }
  }

  /** Reads the first sheet. Throws a 400 when the file is not a readable template. */
  List<MappingImport.RawRow> parse(byte[] file) {
    return Xlsx.readFirstSheet(
            file,
            TEMPLATE_HEADER.getFirst(),
            new int[] {1, 2, 4, 5, 6},
            "The file does not use the mapping template",
            MAX_ROWS)
        .stream()
        .map(
            r ->
                new MappingImport.RawRow(
                    r.rowNumber(),
                    r.cells().get(0),
                    r.cells().get(1),
                    r.cells().get(2),
                    r.cells().get(3),
                    r.cells().get(4)))
        .toList();
  }

  public record Codes(String floorCode, String canteenCode, String costCenterCode) {}

  public record Change(
      int rowNumber, TargetType type, String code, String name, Codes before, Codes after) {}

  public record MappingImportView(
      String id,
      String fileName,
      String status,
      int rowCount,
      int errorCount,
      List<RowError> errors,
      List<Change> changes,
      Instant appliedAt) {}

  /**
   * BR-64: validates every row and stores the upload. One invalid row rejects the whole file
   * (status REJECTED, nothing applied); otherwise the upload waits as a PREVIEW.
   */
  @Transactional
  public MappingImportView upload(String actorId, String factoryId, String fileName, byte[] file) {
    List<MappingImport.RawRow> raw = parse(file);
    Lookups lookups = lookups(factoryId);
    MappingImport.Result result = MappingImport.validate(raw, lookups.rules());
    Instant now = clock.now();
    String id = Ids.newId(now.toEpochMilli());
    String name = fileName == null ? "" : fileName;
    jdbc.sql(
            """
            INSERT INTO "MappingImport" (id, "factoryId", "fileName", "uploadedById", status,
                "rowCount", "errorCount", rows, errors, "createdAt")
            VALUES (:id, :factoryId, :fileName, :actorId, CAST(:status AS "MappingImportStatus"),
                :rowCount, :errorCount, CAST(:rows AS jsonb), CAST(:errors AS jsonb), :now)
            """)
        .param("id", id)
        .param("factoryId", factoryId)
        .param("fileName", name.length() > 200 ? name.substring(0, 200) : name)
        .param("actorId", actorId)
        .param("status", result.ok() ? "PREVIEW" : "REJECTED")
        .param("rowCount", raw.size())
        .param("errorCount", result.errors().size())
        .param("rows", json.writeValueAsString(result.rows()))
        .param("errors", json.writeValueAsString(result.errors()))
        .param("now", SqlTime.utc(now))
        .update();
    return view(id, factoryId);
  }

  private record Stored(
      String id,
      String factoryId,
      String fileName,
      String status,
      int rowCount,
      int errorCount,
      String rows,
      String errors,
      Instant appliedAt) {}

  private Stored load(String importId) {
    return jdbc.sql(
            """
            SELECT id, "factoryId", "fileName", status::text, "rowCount", "errorCount",
                   rows::text, errors::text, "appliedAt"
            FROM "MappingImport" WHERE id = :id
            """)
        .param("id", importId)
        .query(
            (rs, i) ->
                new Stored(
                    rs.getString("id"),
                    rs.getString("factoryId"),
                    rs.getString("fileName"),
                    rs.getString("status"),
                    rs.getInt("rowCount"),
                    rs.getInt("errorCount"),
                    rs.getString("rows"),
                    rs.getString("errors"),
                    SqlTime.instant(rs, "appliedAt")))
        .optional()
        .orElse(null);
  }

  @Transactional(readOnly = true)
  public MappingImportView view(String importId, String factoryId) {
    Stored row = load(importId);
    if (row == null || !row.factoryId().equals(factoryId)) {
      throw ApiException.notFound("Import not found");
    }
    List<Change> changes =
        row.status().equals("PREVIEW") ? diff(rows(row), lookups(factoryId)) : List.of();
    return new MappingImportView(
        row.id(),
        row.fileName(),
        row.status(),
        row.rowCount(),
        row.errorCount(),
        json.readValue(row.errors(), new TypeReference<List<RowError>>() {}),
        changes,
        row.appliedAt());
  }

  private List<Row> rows(Stored row) {
    return json.readValue(row.rows(), new TypeReference<List<Row>>() {});
  }

  /**
   * BR-64: applies a PREVIEW all at once in one transaction, re-validating against the current
   * master data first. Every changed target is audited. Snapshot rows (forecast, final, adjustment
   * lines) keep the mapping they were written with: they are never touched here.
   */
  @Transactional
  public MappingImportView apply(String actorId, String factoryId, String importId) {
    // Lock the import row so two clicks cannot apply it twice.
    String status =
        jdbc.sql(
                """
                SELECT status::text FROM "MappingImport"
                WHERE id = :id AND "factoryId" = :factoryId FOR UPDATE
                """)
            .param("id", importId)
            .param("factoryId", factoryId)
            .query(String.class)
            .optional()
            .orElseThrow(() -> ApiException.notFound("Import not found"));
    if (!status.equals("PREVIEW")) {
      throw ApiException.invalidState("Import is not a pending preview", null);
    }
    Stored imp = load(importId);
    List<Row> rows = rows(imp);
    Lookups lookups = lookups(factoryId);
    MappingImport.Result recheck =
        MappingImport.validate(
            rows.stream()
                .map(
                    r ->
                        new MappingImport.RawRow(
                            r.rowNumber(),
                            r.type().name(),
                            r.code(),
                            Objects.toString(r.floorCode(), ""),
                            Objects.toString(r.canteenCode(), ""),
                            Objects.toString(r.costCenterCode(), "")))
                .toList(),
            lookups.rules());
    if (!recheck.ok()) {
      throw ApiException.invalidState(
          "Master data changed since the preview; upload the file again",
          Map.of("errors", recheck.errors()));
    }

    List<Change> changes = diff(rows, lookups);
    for (Change c : changes) {
      String reason = "Import " + imp.fileName() + " (" + imp.id() + "), row " + c.rowNumber();
      if (c.type() == TargetType.ORG) {
        Target unit = lookups.units().get(c.code());
        jdbc.sql(
                """
                UPDATE "OrgUnit" SET "floorId" = :floorId, "canteenId" = :canteenId,
                    "costCenterId" = :costCenterId
                WHERE id = :id
                """)
            .param("floorId", idOf(lookups.floorIds(), c.after().floorCode()))
            .param("canteenId", idOf(lookups.canteenIds(), c.after().canteenCode()))
            .param("costCenterId", idOf(lookups.costCenterIds(), c.after().costCenterCode()))
            .param("id", unit.id())
            .update();
        audit.log(
            AuditService.Entry.of(actorId, "orgUnit.mapping.updated", "OrgUnit", unit.id())
                .withBefore(c.before())
                .withAfter(c.after())
                .withReason(reason));
      } else {
        Target employee = lookups.employees().get(c.code());
        jdbc.sql("UPDATE \"Employee\" SET \"floorOverrideId\" = :floorId WHERE id = :id")
            .param("floorId", idOf(lookups.floorIds(), c.after().floorCode()))
            .param("id", employee.id())
            .update();
        Map<String, Object> before = new HashMap<>();
        before.put("floorCode", c.before().floorCode());
        Map<String, Object> after = new HashMap<>();
        after.put("floorCode", c.after().floorCode());
        audit.log(
            AuditService.Entry.of(actorId, "employee.floorOverride.set", "Employee", employee.id())
                .withBefore(before)
                .withAfter(after)
                .withReason(reason));
      }
    }
    jdbc.sql(
            """
            UPDATE "MappingImport" SET status = 'APPLIED', "appliedAt" = :now WHERE id = :id
            """)
        .param("now", SqlTime.utc(clock.now()))
        .param("id", importId)
        .update();
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("fileName", imp.fileName());
    after.put("rowCount", imp.rowCount());
    after.put("changed", changes.size());
    audit.log(
        AuditService.Entry.of(actorId, "mapping.import.applied", "MappingImport", importId)
            .withAfter(after));
    return view(importId, factoryId);
  }

  private static String idOf(Map<String, String> ids, String code) {
    return code == null ? null : ids.get(code);
  }

  private static List<Change> diff(List<Row> rows, Lookups lookups) {
    List<Change> changes = new ArrayList<>();
    for (Row r : rows) {
      Codes after = new Codes(r.floorCode(), r.canteenCode(), r.costCenterCode());
      Target target =
          (r.type() == TargetType.ORG ? lookups.units() : lookups.employees()).get(r.code());
      if (target == null) {
        continue;
      }
      if (!target.codes().equals(after)) {
        changes.add(
            new Change(r.rowNumber(), r.type(), r.code(), target.name(), target.codes(), after));
      }
    }
    return changes;
  }

  /** An org unit or an employee with its current own codes (employees: pick-up floor only). */
  private record Target(String id, String name, Codes codes) {}

  private record Lookups(
      Map<String, Target> units,
      Map<String, Target> employees,
      Set<String> activeFloors,
      Set<String> activeCanteens,
      Set<String> activeCostCenters,
      Map<String, String> floorIds,
      Map<String, String> canteenIds,
      Map<String, String> costCenterIds) {

    MappingImport.Lookups rules() {
      return new MappingImport.Lookups(
          units.keySet(), employees.keySet(), activeFloors, activeCanteens, activeCostCenters);
    }
  }

  private record Master(String id, String code, boolean active) {}

  private Lookups lookups(String factoryId) {
    Map<String, Target> units = new HashMap<>();
    jdbc.sql(
            """
            SELECT o.id, o."dmpCode", o.name, f.code AS floor, c.code AS canteen, cc.code AS cc
            FROM "OrgUnit" o
            LEFT JOIN "Floor" f ON f.id = o."floorId"
            LEFT JOIN "Canteen" c ON c.id = o."canteenId"
            LEFT JOIN "CostCenter" cc ON cc.id = o."costCenterId"
            WHERE o."factoryId" = :factoryId
            """)
        .param("factoryId", factoryId)
        .query(
            (rs, i) ->
                units.put(
                    rs.getString("dmpCode"),
                    new Target(
                        rs.getString("id"),
                        rs.getString("name"),
                        new Codes(
                            rs.getString("floor"), rs.getString("canteen"), rs.getString("cc")))))
        .list();
    Map<String, Target> employees = new HashMap<>();
    jdbc.sql(
            """
            SELECT e.id, e."employeeCode", e.name, f.code AS floor
            FROM "Employee" e JOIN "OrgUnit" o ON o.id = e."orgUnitId"
            LEFT JOIN "Floor" f ON f.id = e."floorOverrideId"
            WHERE o."factoryId" = :factoryId
            """)
        .param("factoryId", factoryId)
        .query(
            (rs, i) ->
                employees.put(
                    rs.getString("employeeCode"),
                    new Target(
                        rs.getString("id"),
                        rs.getString("name"),
                        new Codes(rs.getString("floor"), null, null))))
        .list();
    List<Master> floors = master("Floor", factoryId);
    List<Master> canteens = master("Canteen", factoryId);
    List<Master> costCenters = master("CostCenter", factoryId);
    return new Lookups(
        units,
        employees,
        active(floors),
        active(canteens),
        active(costCenters),
        ids(floors),
        ids(canteens),
        ids(costCenters));
  }

  private List<Master> master(String table, String factoryId) {
    return jdbc.sql("SELECT id, code, active FROM \"" + table + "\" WHERE \"factoryId\" = :f")
        .param("f", factoryId)
        .query(Master.class)
        .list();
  }

  private static Set<String> active(List<Master> rows) {
    Set<String> codes = new HashSet<>();
    rows.stream().filter(Master::active).forEach(r -> codes.add(r.code()));
    return codes;
  }

  private static Map<String, String> ids(List<Master> rows) {
    Map<String, String> byCode = new HashMap<>();
    rows.forEach(r -> byCode.put(r.code(), r.id()));
    return byCode;
  }
}
