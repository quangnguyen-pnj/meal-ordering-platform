package vn.pnj.meal.api.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.pnj.meal.api.access.SystemAdminBootstrap;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.common.Xlsx;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.dmp.DmpClient;
import vn.pnj.meal.api.dmp.DmpException;
import vn.pnj.meal.api.dmp.MockDmpClient;
import vn.pnj.meal.api.jobs.Jobs;
import vn.pnj.meal.api.registrations.ForecastService;
import vn.pnj.meal.api.seed.SeedEmployees.Demo;
import vn.pnj.meal.api.settings.SettingsService;
import vn.pnj.meal.api.support.IntegrationTest;
import vn.pnj.meal.api.support.TestXlsx;

/** Port of apps/api-legacy/test/org.e2e-spec.ts. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrgTest extends IntegrationTest {
  @Autowired JsonMapper jsonMapper;
  @Autowired TransactionTemplate tx;
  @Autowired AuditService audit;
  @Autowired SystemAdminBootstrap bootstrap;
  @Autowired SettingsService settings;
  @Autowired MealUnits mealUnits;
  @Autowired ForecastService forecast;
  @Autowired Jobs jobs;

  private Cookie hc;
  private Cookie sys;
  private Cookie employee;
  private Cookie deptProd;
  private byte[] exported;

  @BeforeEach
  void signInAll() throws Exception {
    hc = signInReady(Demo.HC_ADMIN);
    sys = signInReady(Demo.SYS_ADMIN);
    employee = signInReady("PNJP000080");
    deptProd = signInReady(Demo.DEPT_ADMIN_PRODUCTION);
  }

  @AfterAll
  void cleanUp() {
    jdbc.sql("DELETE FROM \"Employee\" WHERE \"employeeCode\" = 'PNJP999001'").update();
    jdbc.sql("DELETE FROM \"OrgUnit\" WHERE \"dmpCode\" = 'TEST-NEW'").update();
    jdbc.sql("DELETE FROM \"CostCenter\" WHERE code = 'CC-DMP-NEW'").update();
  }

  private List<Map<String, Object>> tree() throws Exception {
    return JsonPath.read(read(hc, "/api/admin/org/tree"), "$.items");
  }

  private static Map<String, Object> byCode(List<Map<String, Object>> items, String code) {
    return items.stream().filter(u -> code.equals(u.get("dmpCode"))).findFirst().orElseThrow();
  }

  /** The own mapping of every org node and every employee override, to prove "nothing changed". */
  private List<Map<String, Object>> mappingSnapshot() {
    List<Map<String, Object>> rows = new ArrayList<>();
    rows.addAll(
        jdbc.sql(
                """
                SELECT id, "isMealUnit", "floorId", "canteenId", "costCenterId" FROM "OrgUnit"
                ORDER BY id
                """)
            .query()
            .listOfRows());
    rows.addAll(
        jdbc.sql(
                """
                SELECT "employeeCode", "floorOverrideId" FROM "Employee"
                WHERE "floorOverrideId" IS NOT NULL ORDER BY "employeeCode"
                """)
            .query()
            .listOfRows());
    return rows;
  }

  private int auditCount() {
    return jdbc.sql("SELECT count(*) FROM \"AuditLog\"").query(Integer.class).single();
  }

  private static List<String> orgRow(String code, String floor, String canteen, String cc) {
    return Arrays.asList("ORG", code, "", floor, canteen, cc);
  }

  private static byte[] mappingFile(List<List<String>> rows) throws Exception {
    return TestXlsx.build(MappingImportService.TEMPLATE_HEADER, rows);
  }

  private MockHttpServletResponse upload(byte[] file, String name) throws Exception {
    MockHttpServletResponse res =
        mvc.perform(
                multipart("/api/admin/org/mapping/imports")
                    .file(new MockMultipartFile("file", name, Xlsx.XLSX.toString(), file))
                    .cookie(hc))
            .andReturn()
            .getResponse();
    res.setCharacterEncoding("UTF-8");
    return res;
  }

  private String uploadOk(byte[] file) throws Exception {
    MockHttpServletResponse res = upload(file, "mapping.xlsx");
    assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(201);
    return res.getContentAsString();
  }

  private String floorOf(String table, String column, String where, String value) {
    return jdbc.sql("SELECT \"" + column + "\" FROM \"" + table + "\" WHERE " + where + " = :v")
        .param("v", value)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  // --- org tree (BR-61, BR-62) ---------------------------------------------------------------

  @Test
  @Order(1)
  @DisplayName("BR-61: shows up to 6 levels with the DMP level name")
  void sixLevels() throws Exception {
    List<Map<String, Object>> items = tree();
    assertThat(items.stream().mapToInt(u -> (Integer) u.get("level")).max().orElseThrow())
        .isEqualTo(6);
    assertThat(byCode(items, "XCT-A1-1"))
        .containsEntry("level", 6)
        .containsEntry("dmpLevelName", "Nhóm");
  }

  @Test
  @Order(2)
  @DisplayName("BR-62: inherited values name the node they come from")
  void inheritance() throws Exception {
    List<Map<String, Object>> items = tree();
    Map<String, Object> team = byCode(items, "XCT-A1"); // Tổ Nhẫn
    assertThat(JsonPath.<Object>read(team, "$.own.floorId")).isNull();
    assertThat(JsonPath.<Map<String, Object>>read(team, "$.resolved.floor"))
        .isEqualTo(Map.of("id", "fl_T2", "sourceNodeId", "ou_XCT", "inherited", true));
    assertThat(JsonPath.<Map<String, Object>>read(team, "$.resolved.canteen"))
        .isEqualTo(Map.of("id", "ct_gv", "sourceNodeId", "ou_PNJP-GV", "inherited", true));
    assertThat(team).containsEntry("isMealUnit", true).containsEntry("mealUnitValid", true);
    // BR-63: no floor anywhere above
    assertThat(byCode(items, "XSC-1")).containsEntry("mealUnitValid", false);
  }

  @Test
  @Order(3)
  @DisplayName("BR-60: every node names the meal unit its employees belong to")
  void mealUnitOfEveryNode() throws Exception {
    List<Map<String, Object>> items = tree();
    Map<String, Object> team = byCode(items, "XCT-A1");
    assertThat(team.get("mealUnitId")).isEqualTo(team.get("id"));
    assertThat(byCode(items, "XCT-A1-1").get("mealUnitId")).isEqualTo(team.get("id"));
    Map<String, Object> warehouse =
        items.stream().filter(u -> "Kho vận".equals(u.get("name"))).findFirst().orElseThrow();
    assertThat(warehouse.get("mealUnitId")).isNull();
  }

  // --- meal-unit flags and lists (BR-60, BR-63) -------------------------------------------------

  @Test
  @Order(10)
  @DisplayName(
      "BR-60 / BR-63: the unassigned list says why; the missing-cost-center list is separate")
  void lists() throws Exception {
    String unassigned = read(hc, "/api/admin/org/unassigned");
    assertThat(
            JsonPath.<List<String>>read(
                unassigned, "$.items[?(@.employeeCode == '" + Demo.UNASSIGNED + "')].mealStatus"))
        .containsExactly("NO_MEAL_UNIT");
    List<Map<String, Object>> invalid =
        JsonPath.read(unassigned, "$.items[?(@.employeeCode == '" + Demo.INVALID_MEAL_UNIT + "')]");
    assertThat(invalid).hasSize(1);
    assertThat(invalid.getFirst())
        .containsEntry("mealStatus", "INVALID_MEAL_UNIT")
        .containsEntry("missing", List.of("floor"));
    assertThat(JsonPath.<List<String>>read(unassigned, "$.items[*].employeeCode"))
        .doesNotContain(Demo.EMPLOYEE);

    String missing = read(hc, "/api/admin/org/missing-cost-center");
    assertThat(JsonPath.<List<Object>>read(missing, "$.items")).isNotEmpty();
    assertThat(JsonPath.<List<Object>>read(missing, "$.items[*].costCenterId")).containsOnlyNulls();
    assertThat(JsonPath.<List<String>>read(missing, "$.items[*].orgPath"))
        .allMatch(p -> p.contains("Kho vận"));
  }

  @Test
  @Order(11)
  @DisplayName("BR-60: flagging a node recomputes meal units at once, and it is audited")
  void flagRecomputes() throws Exception {
    expect(200, hc, HttpMethod.PATCH, "/api/admin/org/units/ou_KHO", "{'isMealUnit':true}");
    assertThat(floorOf("Employee", "mealUnitId", "\"employeeCode\"", Demo.UNASSIGNED))
        .isEqualTo("ou_KHO");
    assertThat(
            JsonPath.<List<String>>read(
                read(hc, "/api/admin/org/unassigned"), "$.items[*].employeeCode"))
        .doesNotContain(Demo.UNASSIGNED);

    expect(200, hc, HttpMethod.PATCH, "/api/admin/org/units/ou_KHO", "{'isMealUnit':false}");
    assertThat(floorOf("Employee", "mealUnitId", "\"employeeCode\"", Demo.UNASSIGNED)).isNull();
    List<String> afters =
        jdbc.sql(
                """
                SELECT after::text FROM "AuditLog"
                WHERE "entityId" = 'ou_KHO' AND action = 'orgUnit.mapping.updated'
                ORDER BY at DESC, id DESC LIMIT 2
                """)
            .query(String.class)
            .list();
    assertThat(afters).containsExactly("{\"isMealUnit\": false}", "{\"isMealUnit\": true}");
  }

  @Test
  @Order(12)
  @DisplayName("BR-62: a node maps to active master data only; null clears its own value")
  void activeMasterDataOnly() throws Exception {
    expect(400, hc, HttpMethod.PATCH, "/api/admin/org/units/ou_XSC", "{'floorId':'nope'}");
    expect(200, hc, HttpMethod.PATCH, "/api/admin/org/units/ou_XSC", "{'floorId':'fl_T4'}");
    assertThat(byCode(tree(), "XSC-1")).containsEntry("mealUnitValid", true);
    expect(200, hc, HttpMethod.PATCH, "/api/admin/org/units/ou_XSC", "{'floorId':null}");
    assertThat(byCode(tree(), "XSC-1")).containsEntry("mealUnitValid", false);
    expect(400, hc, HttpMethod.PATCH, "/api/admin/org/units/ou_XSC", "{}");
  }

  // --- assigning dept admins --------------------------------------------------------------------

  @Test
  @Order(20)
  @DisplayName(
      "the HC admin assigns a dept admin to a node, whose scope then covers that branch only")
  void assignDeptAdmin() throws Exception {
    String newAdmin = "PNJP000090";
    String created =
        expect(
            201,
            hc,
            HttpMethod.POST,
            "/api/admin/org/units/ou_KD/dept-admins",
            "{'employeeCode':'" + newAdmin + "'}");
    String assignmentId = JsonPath.read(created, "$.id");
    Cookie admin = signInReady(newAdmin);
    List<String> paths =
        JsonPath.read(read(admin, "/api/employees?limit=100"), "$.items[*].orgPath");
    assertThat(paths).isNotEmpty().allMatch(p -> p.contains("Phòng Kinh doanh"));
    Map<String, Object> kd =
        tree().stream().filter(u -> "ou_KD".equals(u.get("id"))).findFirst().orElseThrow();
    assertThat(JsonPath.<List<String>>read(kd, "$.deptAdmins[*].employeeCode"))
        .containsExactly(newAdmin);

    expect(
        200, hc, HttpMethod.POST, "/api/admin/org/dept-admins/" + assignmentId + "/revoke", null);
    expect(403, admin, HttpMethod.GET, "/api/employees", null);
    // Kept for the record, never deleted.
    assertThat(floorOf("RoleAssignment", "revokedAt", "id", assignmentId)).isNotNull();
  }

  @Test
  @Order(21)
  @DisplayName("rejects an unknown employee")
  void unknownEmployee() throws Exception {
    expect(
        400,
        hc,
        HttpMethod.POST,
        "/api/admin/org/units/ou_KD/dept-admins",
        "{'employeeCode':'NOPE'}");
  }

  // --- BR-64: mapping export and import ---------------------------------------------------------

  @Test
  @Order(30)
  @DisplayName("BR-64: exports the import template with the current own mapping")
  void export() throws Exception {
    MockHttpServletResponse res = send(hc, HttpMethod.GET, "/api/admin/org/mapping/export", null);
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(res.getContentType()).contains("spreadsheetml");
    assertThat(res.getHeader("Content-Disposition")).contains("mapping-to-chuc.xlsx");
    exported = res.getContentAsByteArray();
    List<List<String>> rows = TestXlsx.read(exported, 0, 1, 2, 4, 5, 6);
    assertThat(rows.getFirst().getFirst())
        .isEqualTo(MappingImportService.TEMPLATE_HEADER.getFirst());
    assertThat(rows).contains(List.of("ORG", "XCT", "T2", "", "CC03"));
    assertThat(rows).contains(List.of("ORG", "PNJP-GV", "", "CT-GV", ""));
    assertThat(rows.stream().filter(r -> r.getFirst().equals("ORG")))
        .hasSize(jdbc.sql("SELECT count(*) FROM \"OrgUnit\"").query(Integer.class).single());
    assertThat(TestXlsx.sheetName(exported, 1)).isEqualTo("Danh mục");
  }

  @Test
  @Order(31)
  @DisplayName(
      "BR-64 exit criterion: one bad row rejects the file with row-level errors and applies"
          + " nothing")
  void badRowRejectsFile() throws Exception {
    List<Map<String, Object>> before = mappingSnapshot();
    int auditsBefore = auditCount();
    byte[] file =
        mappingFile(
            List.of(
                orgRow("XCT", "T1", null, "CC03"), // valid on its own
                orgRow("KVP", "T9", null, null), // unknown floor
                orgRow("NOPE", null, null, null), // unknown node
                Arrays.asList("EMPLOYEE", Demo.EMPLOYEE, "", "T1", null, "CC01")));
    String view = uploadOk(file);
    assertThat(JsonPath.<String>read(view, "$.status")).isEqualTo("REJECTED");
    assertThat(JsonPath.<Integer>read(view, "$.rowCount")).isEqualTo(4);
    assertThat(JsonPath.<List<Map<String, Object>>>read(view, "$.errors"))
        .containsExactly(
            Map.of("rowNumber", 3, "field", "floorCode", "code", "FLOOR_UNKNOWN", "value", "T9"),
            Map.of("rowNumber", 4, "field", "code", "code", "ORG_UNKNOWN", "value", "NOPE"),
            Map.of(
                "rowNumber",
                5,
                "field",
                "costCenterCode",
                "code",
                "EMPLOYEE_CANTEEN_OR_COST_CENTER",
                "value",
                "CC01"));
    assertThat(mappingSnapshot()).isEqualTo(before);
    assertThat(auditCount()).isEqualTo(auditsBefore);
    String apply =
        expect(
            409,
            hc,
            HttpMethod.POST,
            "/api/admin/org/mapping/imports/" + JsonPath.read(view, "$.id") + "/apply",
            null);
    assertThat(JsonPath.<String>read(apply, "$.code")).isEqualTo("INVALID_STATE");
    assertThat(mappingSnapshot()).isEqualTo(before);
  }

  @Test
  @Order(32)
  @DisplayName("BR-64: a valid file previews its changes and applies them all at once, audited")
  void previewAndApply() throws Exception {
    List<Map<String, Object>> before = mappingSnapshot();
    byte[] file =
        mappingFile(
            List.of(
                orgRow("XCT", "T1", null, "CC03"), // floor T2 -> T1
                orgRow("KHO-XH", null, null, "CC09"), // cost center for the "missing" list
                orgRow("IT", "T4", null, "CC06"), // unchanged: not in the preview
                Arrays.asList("EMPLOYEE", Demo.EMPLOYEE, "", "T1", null, null)));
    String preview = uploadOk(file);
    String id = JsonPath.read(preview, "$.id");
    assertThat(JsonPath.<String>read(preview, "$.status")).isEqualTo("PREVIEW");
    assertThat(JsonPath.<List<String>>read(preview, "$.changes[*].code"))
        .containsExactly("XCT", "KHO-XH", Demo.EMPLOYEE);
    assertThat(JsonPath.<List<String>>read(preview, "$.changes[*].type"))
        .containsExactly("ORG", "ORG", "EMPLOYEE");
    Map<String, Object> nullCanteen = new java.util.HashMap<>();
    nullCanteen.put("floorCode", "T2");
    nullCanteen.put("canteenCode", null);
    nullCanteen.put("costCenterCode", "CC03");
    assertThat(JsonPath.<Map<String, Object>>read(preview, "$.changes[0].before"))
        .isEqualTo(nullCanteen);
    assertThat(JsonPath.<String>read(preview, "$.changes[0].after.floorCode")).isEqualTo("T1");
    assertThat(mappingSnapshot()).isEqualTo(before); // a preview applies nothing

    String applied =
        expect(200, hc, HttpMethod.POST, "/api/admin/org/mapping/imports/" + id + "/apply", null);
    assertThat(JsonPath.<String>read(applied, "$.status")).isEqualTo("APPLIED");
    assertThat(JsonPath.<String>read(applied, "$.appliedAt")).isNotNull();
    assertThat(floorOf("OrgUnit", "floorId", "id", "ou_XCT")).isEqualTo("fl_T1");
    assertThat(floorOf("Employee", "floorOverrideId", "\"employeeCode\"", Demo.EMPLOYEE))
        .isEqualTo("fl_T1");
    String reason =
        jdbc.sql(
                """
                SELECT reason FROM "AuditLog"
                WHERE "entityId" = 'ou_XCT' AND action = 'orgUnit.mapping.updated'
                ORDER BY at DESC, id DESC LIMIT 1
                """)
            .query(String.class)
            .single();
    assertThat(reason).contains(id);
    expect(409, hc, HttpMethod.POST, "/api/admin/org/mapping/imports/" + id + "/apply", null);
  }

  @Test
  @Order(33)
  @DisplayName("BR-64: re-importing the exported file restores the mapping (blank = inherit)")
  void reimportExport() throws Exception {
    MockHttpServletResponse res = upload(exported, "export.xlsx");
    assertThat(res.getStatus()).isEqualTo(201);
    String preview = res.getContentAsString();
    assertThat(JsonPath.<String>read(preview, "$.status")).isEqualTo("PREVIEW");
    // The exported file has no row for the employee, so their pick-up floor stays.
    assertThat(JsonPath.<List<String>>read(preview, "$.changes[*].code"))
        .containsExactlyInAnyOrder("KHO-XH", "XCT");
    expect(
        200,
        hc,
        HttpMethod.POST,
        "/api/admin/org/mapping/imports/" + JsonPath.read(preview, "$.id") + "/apply",
        null);
    expect(
        200,
        hc,
        HttpMethod.PUT,
        "/api/employees/" + Demo.EMPLOYEE + "/floor-override",
        "{'floorId':null,'reason':'Về lại tầng của đơn vị'}");
    assertThat(floorOf("OrgUnit", "floorId", "id", "ou_XCT")).isEqualTo("fl_T2");
  }

  @Test
  @Order(34)
  @DisplayName("rejects a file that is not the template, and a request without a file")
  void notTheTemplate() throws Exception {
    MockHttpServletResponse res =
        upload(TestXlsx.build(List.of("Something else"), List.of(List.of("x"))), "x.xlsx");
    assertThat(res.getStatus()).isEqualTo(400);
    assertThat(JsonPath.<List<Map<String, Object>>>read(res.getContentAsString(), "$.details"))
        .containsExactly(Map.of("path", "file", "message", "TEMPLATE_MISMATCH"));
    assertThat(upload("not a spreadsheet".getBytes(), "x.xlsx").getStatus()).isEqualTo(400);
    MockHttpServletResponse none =
        mvc.perform(multipart("/api/admin/org/mapping/imports").cookie(hc))
            .andReturn()
            .getResponse();
    assertThat(none.getStatus()).isEqualTo(400);
    expect(400, hc, HttpMethod.POST, "/api/admin/org/mapping/imports", null);
  }

  // --- BR-64 exit criterion: snapshots keep their mapping ----------------------------------------

  @Test
  @Order(40)
  @DisplayName("BR-64: forecast lines keep the mapping they were written with; they are write-once")
  void snapshotsKeepTheirMapping() throws Exception {
    String employeeId = employeeId(Demo.EMPLOYEE);
    long now = clock.now().toEpochMilli();
    String dish = Ids.newId(now);
    String week = Ids.newId(now);
    String day = Ids.newId(now);
    String item = Ids.newId(now);
    String line = Ids.newId(now);
    LocalDate date = LocalDate.of(2027, 6, 7);
    jdbc.sql(
            """
            INSERT INTO "Dish" (id, "factoryId", "canteenId", name, "typeId", sessions)
            VALUES (:id, 'fac_gv', 'ct_gv', 'Snapshot test', 'dt_fac_gv_MAN', '{LUNCH}')
            """)
        .param("id", dish)
        .update();
    jdbc.sql(
            """
            INSERT INTO "MenuWeek" (id, "factoryId", "canteenId", "weekStart", "updatedAt")
            VALUES (:id, 'fac_gv', 'ct_gv', :date, now())
            """)
        .param("id", week)
        .param("date", date)
        .update();
    jdbc.sql(
            """
            INSERT INTO "MenuDay" (id, "menuWeekId", date, "isServing") VALUES (:id, :w, :date, true)
            """)
        .param("id", day)
        .param("w", week)
        .param("date", date)
        .update();
    jdbc.sql(
            """
            INSERT INTO "MenuItem" (id, "menuDayId", "dishId", "sortOrder") VALUES (:id, :d, :dish, 0)
            """)
        .param("id", item)
        .param("d", day)
        .param("dish", dish)
        .update();
    jdbc.sql(
            """
            INSERT INTO "ForecastLine" (id, "menuWeekId", "employeeId", date, session, "menuItemId",
                quantity, "mealUnitId", "floorId", "canteenId", "costCenterId")
            VALUES (:id, :w, :e, :date, 'LUNCH', :item, 2, 'ou_XCT-A1', 'fl_T2', 'ct_gv', 'cc_CC03')
            """)
        .param("id", line)
        .param("w", week)
        .param("e", employeeId)
        .param("date", date)
        .param("item", item)
        .update();
    Map<String, Object> lineBefore = lineRow(line);

    // Change the mapping the line was taken under, through the screen and through an import.
    expect(200, hc, HttpMethod.PATCH, "/api/admin/org/units/ou_XCT-A1", "{'floorId':'fl_T4'}");
    String preview = uploadOk(mappingFile(List.of(orgRow("XCT", "T1", null, "CC04"))));
    expect(
        200,
        hc,
        HttpMethod.POST,
        "/api/admin/org/mapping/imports/" + JsonPath.read(preview, "$.id") + "/apply",
        null);
    expect(
        200,
        hc,
        HttpMethod.PUT,
        "/api/employees/" + Demo.EMPLOYEE + "/floor-override",
        "{'floorId':'fl_T1','reason':'Ngồi ở tầng 1'}");

    assertThat(lineRow(line)).isEqualTo(lineBefore);
    // Rule 7: the database itself refuses to change or delete a snapshot row.
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE \"ForecastLine\" SET \"floorId\" = 'fl_T4' WHERE id = :id")
                    .param("id", line)
                    .update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("write-once");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                jdbc.sql("DELETE FROM \"ForecastLine\" WHERE id = :id").param("id", line).update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("write-once");

    // Put the seed mapping back for the other test classes.
    expect(200, hc, HttpMethod.PATCH, "/api/admin/org/units/ou_XCT-A1", "{'floorId':null}");
    jdbc.sql(
            """
            UPDATE "OrgUnit" SET "floorId" = 'fl_T2', "costCenterId" = 'cc_CC03' WHERE id = 'ou_XCT'
            """)
        .update();
    expect(
        200,
        hc,
        HttpMethod.PUT,
        "/api/employees/" + Demo.EMPLOYEE + "/floor-override",
        "{'floorId':null,'reason':'Về lại tầng của đơn vị'}");
  }

  private Map<String, Object> lineRow(String id) {
    return jdbc.sql("SELECT * FROM \"ForecastLine\" WHERE id = :id")
        .param("id", id)
        .query()
        .singleRow();
  }

  // --- org and employee sync (plan section 5, BR-60, BR-61) --------------------------------------

  private OrgSyncService syncWith(DmpClient dmp) {
    return new OrgSyncService(
        jdbc, tx, jsonMapper, clock, audit, bootstrap, settings, mealUnits, forecast, dmp);
  }

  /** The mock DMP with extra nodes and employees, and renamed nodes. */
  private DmpClient extended(
      List<DmpClient.OrgNode> extraNodes,
      List<DmpClient.Employee> extraEmployees,
      Map<String, String> rename) {
    MockDmpClient base = new MockDmpClient("on-time", jsonMapper);
    return new DmpClient() {
      @Override
      public List<OrgNode> getOrgTree() {
        List<OrgNode> nodes = new ArrayList<>();
        for (OrgNode n : base.getOrgTree()) {
          nodes.add(
              new OrgNode(
                  n.code(),
                  rename.getOrDefault(n.code(), n.name()),
                  n.parentCode(),
                  n.levelName(),
                  n.costCenterCode()));
        }
        nodes.addAll(extraNodes);
        return nodes;
      }

      @Override
      public List<Employee> getEmployees() {
        List<Employee> all = new ArrayList<>(base.getEmployees());
        all.addAll(extraEmployees);
        return all;
      }

      @Override
      public List<AttendanceRecord> getAttendance(LocalDate date) {
        return base.getAttendance(date);
      }

      @Override
      public List<LeaveRecord> getLeave(LocalDate fromDate, LocalDate toDate) {
        return base.getLeave(fromDate, toDate);
      }
    };
  }

  @Test
  @Order(50)
  @DisplayName(
      "a manual sync against the mock is idempotent on the seeded data and writes a SyncRun")
  void manualSync() throws Exception {
    String res = expect(200, hc, HttpMethod.POST, "/api/admin/org/sync", null);
    assertThat(JsonPath.<String>read(res, "$.status")).isEqualTo("SUCCESS");
    assertThat(JsonPath.<Map<String, Object>>read(res, "$.details.orgUnits"))
        .containsEntry("created", 0)
        .containsEntry("updated", 0)
        .containsEntry("deactivated", 0);
    assertThat(JsonPath.<Map<String, Object>>read(res, "$.details.employees"))
        .containsEntry("created", 0)
        .containsEntry("updated", 0)
        .containsEntry("total", 1700);
    assertThat(JsonPath.<Integer>read(res, "$.details.mealUnitsRecomputed")).isZero();
    assertThat(JsonPath.<List<Object>>read(res, "$.details.skippedEmployees")).isEmpty();
    String runId = JsonPath.read(res, "$.syncRunId");
    Map<String, Object> run =
        jdbc.sql("SELECT type::text, status::text, \"recordCount\" FROM \"SyncRun\" WHERE id = :id")
            .param("id", runId)
            .query()
            .singleRow();
    int units = jdbc.sql("SELECT count(*) FROM \"OrgUnit\"").query(Integer.class).single();
    assertThat(run)
        .containsEntry("type", "ORG_EMPLOYEES")
        .containsEntry("status", "SUCCESS")
        .containsEntry("recordCount", 1700 + units);
    assertThat(floorOf("AuditLog", "action", "\"entityId\"", runId))
        .isEqualTo("sync.orgEmployees.triggered");
  }

  @Test
  @Order(51)
  @DisplayName(
      "BR-61 / BR-60: new nodes get a level and path, renames apply, DMP cost centers are created")
  void newNodes() {
    DmpClient dmp =
        extended(
            List.of(new DmpClient.OrgNode("TEST-NEW", "Phòng Mới", "KVP", "Phòng/Xưởng", null)),
            List.of(
                new DmpClient.Employee(
                    "PNJP999001",
                    "Nhân viên mới",
                    "moi@pnjp.test",
                    "TEST-NEW",
                    "CC-DMP-NEW",
                    LocalDate.of(2027, 1, 4),
                    null),
                new DmpClient.Employee(
                    "PNJP999002", "Không rõ đơn vị", null, "NOPE", null, null, null)),
            Map.of("IT", "Phòng Công nghệ"));
    OrgSyncService.Result result = syncWith(dmp).run("fac_gv", null);
    assertThat(result.status()).isEqualTo("SUCCESS");
    assertThat(result.details().orgUnits().created()).isEqualTo(1);
    assertThat(result.details().orgUnits().updated()).isEqualTo(1);
    assertThat(result.details().employees().created()).isEqualTo(1);
    assertThat(result.details().costCentersCreated()).isEqualTo(1);
    assertThat(result.details().skippedEmployees())
        .containsExactly(new OrgSyncService.Skipped("PNJP999002", "NOPE"));
    Map<String, Object> node =
        jdbc.sql("SELECT * FROM \"OrgUnit\" WHERE \"dmpCode\" = 'TEST-NEW'").query().singleRow();
    assertThat(node)
        .containsEntry("level", 3)
        .containsEntry("parentId", "ou_KVP")
        .containsEntry("isMealUnit", false)
        .containsEntry("path", "/ou_PNJP-GV/ou_KVP/" + node.get("id") + "/");
    assertThat(floorOf("OrgUnit", "name", "id", "ou_IT")).isEqualTo("Phòng Công nghệ");
    Map<String, Object> newcomer =
        jdbc.sql(
                """
                SELECT "mealUnitId", "pinHash", status::text, "dmpCostCenterCode" FROM "Employee"
                WHERE "employeeCode" = 'PNJP999001'
                """)
            .query()
            .singleRow();
    assertThat(newcomer)
        .containsEntry("mealUnitId", null)
        .containsEntry("pinHash", null)
        .containsEntry("status", "ACTIVE")
        .containsEntry("dmpCostCenterCode", "CC-DMP-NEW");
    assertThat(floorOf("CostCenter", "source", "code", "CC-DMP-NEW")).isEqualTo("DMP");
  }

  @Test
  @Order(52)
  @DisplayName("BR-61: a 7-level tree fails the run and writes nothing")
  void tooDeep() {
    List<DmpClient.OrgNode> chain =
        List.of(
            new DmpClient.OrgNode("D1", "D1", "XCT-A1-1", "Sâu", null),
            new DmpClient.OrgNode("D2", "D2", "D1", "Sâu", null),
            new DmpClient.OrgNode("D3", "D3", "D2", "Sâu", null));
    int units = jdbc.sql("SELECT count(*) FROM \"OrgUnit\"").query(Integer.class).single();
    OrgSyncService.Result result =
        syncWith(extended(chain, List.of(), Map.of())).run("fac_gv", null);
    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.error()).contains("TOO_DEEP");
    assertThat(jdbc.sql("SELECT count(*) FROM \"OrgUnit\"").query(Integer.class).single())
        .isEqualTo(units);
    assertThat(floorOf("SyncRun", "status", "id", result.syncRunId())).isEqualTo("FAILED");
  }

  @Test
  @Order(53)
  @DisplayName("a DMP error fails the run")
  void dmpError() {
    DmpClient failing =
        new DmpClient() {
          @Override
          public List<OrgNode> getOrgTree() {
            throw new DmpException(DmpException.Kind.API_ERROR, "DMP is down");
          }

          @Override
          public List<Employee> getEmployees() {
            return List.of();
          }

          @Override
          public List<AttendanceRecord> getAttendance(LocalDate date) {
            return List.of();
          }

          @Override
          public List<LeaveRecord> getLeave(LocalDate fromDate, LocalDate toDate) {
            return List.of();
          }
        };
    OrgSyncService.Result result = syncWith(failing).run("fac_gv", null);
    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.error()).isEqualTo("DMP is down");
  }

  @Test
  @Order(54)
  @DisplayName("nodes gone from DMP are deactivated, not deleted; the job restores the seed names")
  void deactivated() {
    jobs.syncOrgEmployees();
    Map<String, Object> node =
        jdbc.sql("SELECT active FROM \"OrgUnit\" WHERE \"dmpCode\" = 'TEST-NEW'")
            .query()
            .singleRow();
    assertThat(node).containsEntry("active", false);
    assertThat(floorOf("OrgUnit", "name", "id", "ou_IT")).isEqualTo("Phòng IT");
    assertThat(
            jdbc.sql("SELECT status::text FROM \"SyncRun\" ORDER BY \"startedAt\" DESC LIMIT 1")
                .query(String.class)
                .single())
        .isEqualTo("SUCCESS");
  }

  // --- 403 on every org, master and sync endpoint ------------------------------------------------

  @Nested
  @DisplayName("403 on every org, master and sync endpoint")
  class Forbidden {
    @Test
    @DisplayName("an employee and a dept admin (even for a node in their own scope) get 403")
    void forbidden() throws Exception {
      expectForbidden(
          List.of(employee, deptProd),
          List.of(
              Endpoint.get("/api/admin/org/tree"),
              Endpoint.of(HttpMethod.PATCH, "/api/admin/org/units/ou_XCT", "{'isMealUnit':true}"),
              Endpoint.of(
                  HttpMethod.POST,
                  "/api/admin/org/units/ou_XCT/dept-admins",
                  "{'employeeCode':'PNJP000001'}"),
              Endpoint.of(HttpMethod.POST, "/api/admin/org/dept-admins/x/revoke", null),
              Endpoint.get("/api/admin/org/unassigned"),
              Endpoint.get("/api/admin/org/missing-cost-center"),
              Endpoint.get("/api/admin/org/mapping/export"),
              Endpoint.of(HttpMethod.POST, "/api/admin/org/mapping/imports", null),
              Endpoint.get("/api/admin/org/mapping/imports/x"),
              Endpoint.of(HttpMethod.POST, "/api/admin/org/mapping/imports/x/apply", null),
              Endpoint.of(HttpMethod.POST, "/api/admin/org/sync", null),
              Endpoint.get("/api/admin/master/canteens"),
              Endpoint.of(HttpMethod.POST, "/api/admin/master/canteens", "{'code':'X','name':'X'}"),
              Endpoint.of(HttpMethod.PATCH, "/api/admin/master/canteens/ct_gv", "{'name':'X'}"),
              Endpoint.get("/api/admin/master/floors"),
              Endpoint.of(HttpMethod.POST, "/api/admin/master/floors", "{'code':'X','name':'X'}"),
              Endpoint.of(HttpMethod.PATCH, "/api/admin/master/floors/fl_T1", "{'name':'X'}"),
              Endpoint.get("/api/admin/master/cost-centers"),
              Endpoint.of(
                  HttpMethod.POST, "/api/admin/master/cost-centers", "{'code':'X','name':'X'}"),
              Endpoint.of(
                  HttpMethod.PATCH, "/api/admin/master/cost-centers/cc_CC01", "{'name':'X'}"),
              Endpoint.of(
                  HttpMethod.PUT,
                  "/api/employees/" + Demo.EMPLOYEE + "/floor-override",
                  "{'floorId':null,'reason':'x'}")));
    }

    @Test
    @DisplayName("the system admin may manage org and master data (plan section 3 matrix)")
    void systemAdmin() throws Exception {
      read(sys, "/api/admin/org/tree");
      read(sys, "/api/admin/master/floors");
    }
  }
}
