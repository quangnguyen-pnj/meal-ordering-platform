package vn.pnj.meal.api.close;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.pnj.meal.api.attendance.AttendanceService;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.dmp.MockDmpClient;
import vn.pnj.meal.api.jobs.Jobs;
import vn.pnj.meal.api.seed.SeedEmployees.Demo;
import vn.pnj.meal.api.settings.SettingsService;
import vn.pnj.meal.api.support.IntegrationTest;

/**
 * P4 exit criteria (plan 7, P4 "Done when") with fake time and the mock DMP: reconciliation (BR-40
 * to BR-42), finalization (BR-43), attendance after finalization (BR-30, BR-32, v1.4), the
 * caterer's files (BR-44) and adjustments (BR-46).
 *
 * <p>One published week, 2029-04-02, with two dishes a day. Employees with one meal of dish A every
 * day: PNJP000001 and PNJP000008..PNJP000040 (the mock scenarios act on 008..020). PNJP000040 adds
 * dish B on Monday after the forecast.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloseTest extends IntegrationTest {
  private static final String WEEK = "2029-04-02";
  private static final String MON = "2029-04-02";
  private static final String TUE = "2029-04-03";
  private static final String WED = "2029-04-04";
  private static final String THU = "2029-04-05";
  private static final String AFFECTED = "PNJP000010";
  private static final String ON_TIME = "PNJP000030";

  @Autowired JsonMapper jsonMapper;
  @Autowired TransactionTemplate tx;
  @Autowired AuditService audit;
  @Autowired SettingsService settings;
  @Autowired DayCloseStore dayClose;
  @Autowired Jobs jobs;

  private final Map<LocalDate, String[]> items = new HashMap<>();
  private final List<String> withMeals = new ArrayList<>();
  private String factoryId;

  private AttendanceService service(String scenario) {
    return new AttendanceService(
        jdbc,
        tx,
        jsonMapper,
        clock,
        audit,
        settings,
        new MockDmpClient(scenario, jsonMapper, clock::now),
        dayClose);
  }

  private static String code(int n) {
    return "PNJP%06d".formatted(n);
  }

  private void register(String employeeCode, LocalDate date, String item, int quantity) {
    jdbc.sql(
            """
            INSERT INTO "Registration" (id, "employeeId", "menuItemId", date, quantity, source,
                status, "updatedAt")
            VALUES (:id, :e, :item, :date, :q, 'SELF', 'ACTIVE', now())
            """)
        .param("id", Ids.newId(clock.now().toEpochMilli()))
        .param("e", employeeId(employeeCode))
        .param("item", item)
        .param("date", date)
        .param("q", quantity)
        .update();
  }

  private String itemA(String date) {
    return items.get(LocalDate.parse(date))[0];
  }

  private String itemB(String date) {
    return items.get(LocalDate.parse(date))[1];
  }

  private String state(String employeeCode, String date) {
    return jdbc.sql(
            """
            SELECT state::text FROM "DailyPresence"
            WHERE "employeeId" = :e AND date = CAST(:d AS date)
            """)
        .param("e", employeeId(employeeCode))
        .param("d", date)
        .query(String.class)
        .optional()
        .orElse("NONE");
  }

  private long finalCountTotal(String date) {
    return jdbc.sql(
            """
            SELECT COALESCE(SUM(f.quantity), 0) FROM "FinalCount" f
            JOIN "DayClose" d ON d.id = f."dayCloseId" WHERE d.date = CAST(:d AS date)
            """)
        .param("d", date)
        .query(Long.class)
        .single();
  }

  private long finalLineTotal(String date) {
    return jdbc.sql(
            """
            SELECT COALESCE(SUM(f.quantity), 0) FROM "FinalEmployeeLine" f
            JOIN "DayClose" d ON d.id = f."dayCloseId" WHERE d.date = CAST(:d AS date)
            """)
        .param("d", date)
        .query(Long.class)
        .single();
  }

  private long audits(String action, String entityLike) {
    return jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE action = :a AND \"entityId\" LIKE :d")
        .param("a", action)
        .param("d", entityLike)
        .query(Long.class)
        .single();
  }

  private long attendanceRuns(String date) {
    return jdbc.sql(
            "SELECT count(*) FROM \"SyncRun\" WHERE type = 'ATTENDANCE' AND date = CAST(:d AS date)")
        .param("d", date)
        .query(Long.class)
        .single();
  }

  private void tickAt(AttendanceService service, String date, String time) {
    setVnTime(date, time);
    service.tick(factoryId);
  }

  private byte[] download(Cookie who, String url) throws Exception {
    MockHttpServletResponse res = send(who, HttpMethod.GET, url, null);
    assertThat(res.getStatus()).as("GET " + url).isEqualTo(200);
    assertThat(res.getContentType()).contains("spreadsheetml");
    return res.getContentAsByteArray();
  }

  /** The last numeric cell of the first row whose first cell is {@code label}. */
  private static int totalOf(Sheet sheet, String label) {
    for (Row row : sheet) {
      Cell first = row.getCell(0);
      if (first != null
          && first.getCellType() == CellType.STRING
          && first.getStringCellValue().equals(label)) {
        Cell last = row.getCell(row.getLastCellNum() - 1);
        return (int) last.getNumericCellValue();
      }
    }
    throw new AssertionError("no row " + label + " in " + sheet.getSheetName());
  }

  private static String texts(Sheet sheet) {
    StringBuilder out = new StringBuilder();
    for (Row row : sheet) {
      for (Cell cell : row) {
        if (cell.getCellType() == CellType.STRING) {
          out.append(cell.getStringCellValue()).append('|');
        }
      }
    }
    return out.toString();
  }

  @AfterAll
  void removeMeals() {
    jdbc.sql("DELETE FROM \"Registration\" WHERE date BETWEEN '2029-04-02' AND '2029-04-06'")
        .update();
  }

  @Test
  @Order(1)
  @DisplayName("publishes a week with two dishes a day, registers meals and takes the forecast")
  void setUp() throws Exception {
    setVnTime("2029-03-26", "10:00");
    factoryId = jdbc.sql("SELECT id FROM \"Factory\" LIMIT 1").query(String.class).single();
    Cookie hc = signInReady(Demo.HC_ADMIN);
    String[] dishes = new String[2];
    for (int i = 0; i < 2; i++) {
      dishes[i] =
          JsonPath.read(
              expect(
                  201,
                  hc,
                  HttpMethod.POST,
                  "/api/admin/dishes",
                  "{'canteenId':'ct_gv','name':'Món P4 %d','typeId':'dt_fac_gv_MAN','sessions':['LUNCH']}"
                      .formatted(i)),
              "$.id");
    }
    String dessert =
        JsonPath.read(
            expect(
                201,
                hc,
                HttpMethod.POST,
                "/api/admin/desserts",
                "{'canteenId':'ct_gv','name':'Chè P4'}"),
            "$.id");
    List<String> days = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      days.add(
          "{'date':'%s','isServing':true,'items':[{'dishId':'%s'},{'dishId':'%s'}],'dessertIds':['%s']}"
              .formatted(LocalDate.parse(WEEK).plusDays(i), dishes[0], dishes[1], dessert));
    }
    String week =
        expect(
            200,
            hc,
            HttpMethod.POST,
            "/api/admin/menu/weeks/publish",
            "{'canteenId':'ct_gv','weekStart':'%s','days':[%s]}"
                .formatted(WEEK, String.join(",", days)));
    for (int i = 0; i < 5; i++) {
      List<String> ids = JsonPath.read(week, "$.days[" + i + "].items[*].id");
      items.put(LocalDate.parse(WEEK).plusDays(i), ids.toArray(String[]::new));
    }
    withMeals.add(Demo.EMPLOYEE);
    for (int n = 8; n <= 40; n++) {
      withMeals.add(code(n));
    }
    for (LocalDate date : items.keySet()) {
      for (String c : withMeals) {
        register(c, date, items.get(date)[0], 1);
      }
    }
    // After closeAt (Friday 00:00) the first request takes the forecast snapshot (BR-07).
    setVnTime(MON, "07:20");
    hc = signInReady(Demo.HC_ADMIN);
    read(hc, "/api/admin/today?date=" + MON);
    assertThat(
            jdbc.sql("SELECT count(*) FROM \"ForecastLine\" WHERE date = CAST(:d AS date)")
                .param("d", MON)
                .query(Long.class)
                .single())
        .isEqualTo(34);
    // BR-23: an employee adds a meal after closeAt, before the lock.
    register(code(40), LocalDate.parse(MON), itemB(MON), 1);
  }

  @Test
  @Order(2)
  @DisplayName(
      "BR-40 to BR-42: reconciliation compares actual with forecast per meal and lists the cases")
  void reconciliation() throws Exception {
    AttendanceService forgot = service("forgot-to-check-in");
    tickAt(forgot, MON, "07:30");
    tickAt(forgot, MON, "08:10");
    setVnTime(MON, "08:15");
    Cookie hc = signInReady(Demo.HC_ADMIN);
    String view = read(hc, "/api/admin/reconciliation?date=" + MON);
    assertThat(JsonPath.<Integer>read(view, "$.summary.forecast")).isEqualTo(34);
    // 13 provisionally cancelled (008..020): 21 meals of A and the added B.
    assertThat(JsonPath.<Integer>read(view, "$.summary.actual")).isEqualTo(22);
    assertThat(JsonPath.<Integer>read(view, "$.summary.difference")).isEqualTo(-12);
    assertThat(JsonPath.<Integer>read(view, "$.summary.cases")).isEqualTo(14);
    assertThat(JsonPath.<Integer>read(view, "$.summary.deviatingMeals")).isEqualTo(2);
    String a = "$.meals[?(@.menuItemId == '" + itemA(MON) + "')]";
    assertThat(JsonPath.<List<Integer>>read(view, a + ".forecast")).containsExactly(34);
    assertThat(JsonPath.<List<Integer>>read(view, a + ".actual")).containsExactly(21);
    assertThat(JsonPath.<List<String>>read(view, a + ".status")).containsExactly("MISMATCHED");
    assertThat(JsonPath.<List<Boolean>>read(view, a + ".deviates")).containsExactly(true);
    String b = "$.meals[?(@.menuItemId == '" + itemB(MON) + "')]";
    assertThat(JsonPath.<List<Integer>>read(view, b + ".forecast")).containsExactly(0);
    assertThat(JsonPath.<List<Integer>>read(view, b + ".actual")).containsExactly(1);
    assertThat(
            JsonPath.<List<String>>read(
                view, "$.exceptions[?(@.employeeCode == '" + code(40) + "')].cases[*]"))
        .containsExactly("ADDED");
    assertThat(
            JsonPath.<List<String>>read(
                view, "$.exceptions[?(@.employeeCode == '" + AFFECTED + "')].cases[*]"))
        .containsExactly("PROVISIONALLY_CANCELLED");
    // BR-43: not before 08:30.
    assertThat(JsonPath.<Boolean>read(view, "$.canFinalize")).isFalse();
    String early =
        expect(
            409,
            hc,
            HttpMethod.POST,
            "/api/admin/reconciliation/finalize",
            "{'date':'" + MON + "'}");
    assertThat(JsonPath.<String>read(early, "$.code")).isEqualTo("TOO_EARLY");

    // A dept admin sees reconciliation of their units only.
    Cookie dept = signInReady(Demo.DEPT_ADMIN_PRODUCTION);
    String scoped = read(dept, "/api/admin/reconciliation?date=" + MON);
    assertThat(JsonPath.<Integer>read(scoped, "$.summary.forecast")).isLessThanOrEqualTo(34);
    assertThat(JsonPath.<List<String>>read(scoped, "$.exceptions[*].employeeCode"))
        .isSubsetOf(JsonPath.<List<String>>read(view, "$.exceptions[*].employeeCode"));
    assertThat(JsonPath.<List<String>>read(scoped, "$.exceptions[*].orgPath"))
        .allMatch(path -> path.contains("Xưởng Chế tác"));
  }

  @Test
  @Order(3)
  @DisplayName(
      "BR-43: at 08:30 the HC admin finalizes; provisional cancellations become cancellations and"
          + " FinalCount equals the reconciliation screen")
  void finalizeDay() throws Exception {
    setVnTime(MON, "08:30");
    Cookie hc = signInReady(Demo.HC_ADMIN);
    String before = read(hc, "/api/admin/reconciliation?date=" + MON);
    assertThat(JsonPath.<Boolean>read(before, "$.canFinalize")).isTrue();
    String res =
        expect(
            200,
            hc,
            HttpMethod.POST,
            "/api/admin/reconciliation/finalize",
            "{'date':'" + MON + "'}");
    assertThat(JsonPath.<Integer>read(res, "$.cancelled")).isEqualTo(13);
    assertThat(JsonPath.<Integer>read(res, "$.meals")).isEqualTo(22);
    assertThat(JsonPath.<Integer>read(res, "$.employees")).isEqualTo(21);
    assertThat(JsonPath.<Boolean>read(res, "$.autoFinalized")).isFalse();
    assertThat(state(AFFECTED, MON)).isEqualTo("ABSENT");
    assertThat(finalCountTotal(MON)).isEqualTo(22);
    assertThat(finalLineTotal(MON)).isEqualTo(22);
    assertThat(audits("presence.cancelled", "%:" + MON)).isEqualTo(13);
    assertThat(audits("day.finalized", "%")).isGreaterThanOrEqualTo(1);

    String view = read(hc, "/api/admin/reconciliation?date=" + MON);
    assertThat(JsonPath.<Integer>read(view, "$.summary.actual")).isEqualTo(22);
    assertThat(JsonPath.<Integer>read(view, "$.summary.finalCount")).isEqualTo(22);
    assertThat(JsonPath.<Integer>read(view, "$.summary.effective")).isEqualTo(22);
    assertThat(JsonPath.<String>read(view, "$.closed.finalizedByName")).isNotBlank();
    assertThat(
            JsonPath.<List<String>>read(
                view, "$.exceptions[?(@.employeeCode == '" + AFFECTED + "')].cases[*]"))
        .containsExactly("CANCELLED_ABSENT");

    String again =
        expect(
            409,
            hc,
            HttpMethod.POST,
            "/api/admin/reconciliation/finalize",
            "{'date':'" + MON + "'}");
    assertThat(JsonPath.<String>read(again, "$.code")).isEqualTo("DAY_FINALIZED");
  }

  @Test
  @Order(4)
  @DisplayName("BR-43: every normal edit API is rejected for a finalized day")
  void editsRejected() throws Exception {
    setVnTime(MON, "08:35");
    Cookie hc = signInReady(Demo.HC_ADMIN);
    Cookie dept = signInReady(Demo.DEPT_ADMIN_PRODUCTION);
    Cookie employee = signInReady(Demo.EMPLOYEE);
    String lines = "[{'menuItemId':'" + itemA(MON) + "','quantity':2}]";
    for (Cookie who : List.of(hc, dept)) {
      String edit =
          expect(
              409,
              who,
              HttpMethod.PUT,
              "/api/admin/today/employees/" + Demo.EMPLOYEE + "/meals",
              "{'date':'" + MON + "','lines':" + lines + ",'reason':'Sửa sau chốt'}");
      assertThat(JsonPath.<String>read(edit, "$.code")).isEqualTo("DAY_FINALIZED");
      String extra =
          expect(
              409,
              who,
              HttpMethod.POST,
              "/api/admin/registrations/employees/" + Demo.EMPLOYEE + "/extras",
              "{'date':'%s','menuItemId':'%s','quantity':1,'reason':'Thêm'}"
                  .formatted(MON, itemA(MON)));
      assertThat(JsonPath.<String>read(extra, "$.code")).isEqualTo("DAY_FINALIZED");
      String presence =
          expect(
              409,
              who,
              HttpMethod.POST,
              "/api/admin/today/employees/" + AFFECTED + "/presence",
              "{'date':'" + MON + "'}");
      assertThat(JsonPath.<String>read(presence, "$.code")).isEqualTo("DAY_FINALIZED");
    }
    expect(
        409,
        dept,
        HttpMethod.PUT,
        "/api/admin/registrations/units/ou_XCT-A1-1/days/" + MON,
        "{'canteenId':'ct_gv','rows':[{'employeeCode':'"
            + Demo.EMPLOYEE
            + "','lines':"
            + lines
            + "}]}");
    expect(
        409,
        employee,
        HttpMethod.PUT,
        "/api/registrations/me/days/" + MON,
        "{'lines':" + lines + "}");
    String today = read(hc, "/api/admin/today?date=" + MON);
    assertThat(JsonPath.<String>read(today, "$.window")).isEqualTo("CLOSED");
    assertThat(JsonPath.<Boolean>read(today, "$.canConfirmPresence")).isFalse();
    assertThat(finalCountTotal(MON)).isEqualTo(22);
  }

  @Test
  @Order(5)
  @DisplayName(
      "BR-32 (v1.4): a manager's DMP confirmation after finalization adds nothing and only shows as"
          + " a case to review")
  void lateDmpConfirmation() throws Exception {
    long restoredBefore = audits("presence.restored", "%:" + MON);
    tickAt(service("manager-confirmed"), MON, "08:40");
    assertThat(state(AFFECTED, MON)).isEqualTo("ABSENT");
    assertThat(audits("presence.restored", "%:" + MON)).isEqualTo(restoredBefore);
    assertThat(finalCountTotal(MON)).isEqualTo(22);
    Cookie hc = signInReady(Demo.HC_ADMIN);
    String view = read(hc, "/api/admin/reconciliation?date=" + MON);
    assertThat(JsonPath.<Integer>read(view, "$.summary.effective")).isEqualTo(22);
    assertThat(
            JsonPath.<List<String>>read(
                view, "$.exceptions[?(@.employeeCode == '" + AFFECTED + "')].cases[*]"))
        .containsExactly("CANCELLED_ABSENT", "LATE_DMP_CONFIRMATION");
  }

  @Test
  @Order(6)
  @DisplayName(
      "BR-32 (v1.4): after finalization the HC admin's re-run still records until 10:30; no sync"
          + " runs after it")
  void syncAfterFinalization() throws Exception {
    setVnTime(MON, "10:29");
    Cookie hc = signInReady(Demo.HC_ADMIN);
    String res =
        expect(200, hc, HttpMethod.POST, "/api/admin/attendance/sync", "{'date':'" + MON + "'}");
    assertThat(JsonPath.<Object>read(res, "$.cutoff")).isNull();
    assertThat(state(AFFECTED, MON)).isEqualTo("ABSENT");
    setVnTime(MON, "10:30");
    hc = signInReady(Demo.HC_ADMIN);
    String late =
        expect(409, hc, HttpMethod.POST, "/api/admin/attendance/sync", "{'date':'" + MON + "'}");
    assertThat(JsonPath.<String>read(late, "$.code")).isEqualTo("DAY_FINALIZED");
    long runs = attendanceRuns(MON);
    tickAt(service("on-time"), MON, "10:35");
    assertThat(attendanceRuns(MON)).isEqualTo(runs);
  }

  @Test
  @Order(7)
  @DisplayName(
      "BR-44: the final file, the box labels and the weekly forecast file match FinalCount and open"
          + " with Vietnamese text")
  void files() throws Exception {
    setVnTime(MON, "10:40");
    Cookie hc = signInReady(Demo.HC_ADMIN);
    byte[] file = download(hc, "/api/admin/reconciliation/export?date=" + MON);
    try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
      assertThat(wb.getSheetName(0)).isEqualTo("Chốt số 02-04");
      assertThat(wb.getSheetName(1)).isEqualTo("Nhãn hộp cơm");
      Sheet sheet = wb.getSheetAt(0);
      assertThat(totalOf(sheet, "Tổng cộng")).isEqualTo((int) finalCountTotal(MON));
      assertThat(texts(sheet)).contains("Đơn vị nhận cơm", "Tầng", "Món P4 0", "Món P4 1");
      // One label per meal unit row, whose totals add up to the final count.
      int labels = 0;
      for (Row row : wb.getSheetAt(1)) {
        Cell first = row.getCell(0);
        if (first != null
            && first.getCellType() == CellType.STRING
            && first.getStringCellValue().equals("Tổng")) {
          labels += (int) row.getCell(1).getNumericCellValue();
        }
      }
      assertThat(labels).isEqualTo(22);
    }
    byte[] forecast = download(hc, "/api/admin/reconciliation/forecast/export?weekStart=" + WEEK);
    try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(forecast))) {
      assertThat(wb.getNumberOfSheets()).isEqualTo(5);
      assertThat(wb.getSheetName(0)).isEqualTo("T2 02-04");
      for (int i = 0; i < 5; i++) {
        assertThat(totalOf(wb.getSheetAt(i), "Tổng cộng")).isEqualTo(34);
      }
    }
    String tab = read(hc, "/api/admin/reconciliation/forecast?weekStart=" + WEEK);
    assertThat(JsonPath.<Boolean>read(tab, "$.exportable")).isTrue();
    assertThat(JsonPath.<List<Integer>>read(tab, "$.canteens[0].days[*].total"))
        .containsExactly(34, 34, 34, 34, 34);
  }

  @Test
  @Order(8)
  @DisplayName(
      "BR-46: an HC admin adjustment before 10:30 is accepted, leaves FinalCount unchanged and"
          + " changes the effective count")
  void adjustments() throws Exception {
    setVnTime(TUE, "07:00");
    AttendanceService forgot = service("forgot-to-check-in");
    tickAt(forgot, TUE, "07:30");
    tickAt(forgot, TUE, "08:10");
    setVnTime(TUE, "08:30");
    Cookie hc = signInReady(Demo.HC_ADMIN);
    expect(
        200, hc, HttpMethod.POST, "/api/admin/reconciliation/finalize", "{'date':'" + TUE + "'}");
    assertThat(finalCountTotal(TUE)).isEqualTo(21);

    setVnTime(TUE, "09:00");
    hc = signInReady(Demo.HC_ADMIN);
    String add =
        "{'date':'%s','reason':'Về muộn, QL xác nhận','catererConfirmed':%s,'lines':[{'employeeCode':'%s','menuItemId':'%s','delta':1}]}";
    String noTick =
        expect(
            400,
            hc,
            HttpMethod.POST,
            "/api/admin/adjustments",
            add.formatted(TUE, false, Demo.EMPLOYEE, itemA(TUE)));
    assertThat(JsonPath.<List<String>>read(noTick, "$.details[*].code"))
        .containsExactly("CATERER_CONFIRMATION_REQUIRED");
    String noReason =
        expect(
            400,
            hc,
            HttpMethod.POST,
            "/api/admin/adjustments",
            "{'date':'%s','reason':' ','catererConfirmed':true,'lines':[{'employeeCode':'%s','menuItemId':'%s','delta':1}]}"
                .formatted(TUE, Demo.EMPLOYEE, itemA(TUE)));
    assertThat(JsonPath.<List<String>>read(noReason, "$.details[*].code"))
        .containsExactly("REASON_REQUIRED");
    String belowZero =
        expect(
            400,
            hc,
            HttpMethod.POST,
            "/api/admin/adjustments",
            "{'date':'%s','reason':'Bớt','catererConfirmed':false,'lines':[{'employeeCode':'%s','menuItemId':'%s','delta':-2}]}"
                .formatted(TUE, ON_TIME, itemA(TUE)));
    assertThat(JsonPath.<List<String>>read(belowZero, "$.details[*].code"))
        .containsExactly("BELOW_ZERO");

    String created =
        expect(
            201,
            hc,
            HttpMethod.POST,
            "/api/admin/adjustments",
            add.formatted(TUE, true, Demo.EMPLOYEE, itemA(TUE)));
    assertThat(JsonPath.<Integer>read(created, "$.number")).isEqualTo(1);
    String adjustmentId = JsonPath.read(created, "$.id");
    assertThat(finalCountTotal(TUE)).isEqualTo(21);
    String view = read(hc, "/api/admin/reconciliation?date=" + TUE);
    assertThat(JsonPath.<Integer>read(view, "$.summary.finalCount")).isEqualTo(21);
    assertThat(JsonPath.<Integer>read(view, "$.summary.adjustment")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(view, "$.summary.effective")).isEqualTo(22);
    assertThat(audits("adjustment.created", adjustmentId)).isEqualTo(1);

    // Removing a meal needs a reason but no caterer tick.
    expect(
        201,
        hc,
        HttpMethod.POST,
        "/api/admin/adjustments",
        "{'date':'%s','reason':'Về sớm','catererConfirmed':false,'lines':[{'employeeCode':'%s','menuItemId':'%s','delta':-1}]}"
            .formatted(TUE, ON_TIME, itemA(TUE)));
    String list = read(hc, "/api/admin/adjustments?date=" + TUE);
    assertThat(JsonPath.<List<Integer>>read(list, "$.items[*].totalDelta")).containsExactly(1, -1);
    assertThat(JsonPath.<Boolean>read(list, "$.canAdjust")).isTrue();
    String form = read(hc, "/api/admin/adjustments/employees/" + ON_TIME + "?date=" + TUE);
    assertThat(
            JsonPath.<List<Integer>>read(
                form, "$.meals[?(@.menuItemId == '" + itemA(TUE) + "')].effective"))
        .containsExactly(0);

    // The supplementary file holds only the changed cells.
    byte[] file = download(hc, "/api/admin/adjustments/" + adjustmentId + "/export");
    try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
      Sheet sheet = wb.getSheetAt(0);
      assertThat(sheet.getSheetName()).isEqualTo("Điều chỉnh 1");
      assertThat(totalOf(sheet, "Tổng cộng")).isEqualTo(1);
      assertThat(texts(sheet)).contains("Món P4 0").doesNotContain("Món P4 1");
    }

    // The employee sees the effective count, without the reason (product owner, 2026-11-10).
    Cookie affected = signInReady(Demo.EMPLOYEE);
    String day = read(affected, "/api/registrations/me/day?date=" + TUE);
    assertThat(JsonPath.<Integer>read(day, "$.closed.finalTotal")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(day, "$.closed.effectiveTotal")).isEqualTo(2);
    assertThat(JsonPath.<Boolean>read(day, "$.closed.adjusted")).isTrue();
    assertThat(day).doesNotContain("Về muộn");
    String history = read(affected, "/api/registrations/me/history?from=" + TUE + "&to=" + TUE);
    assertThat(JsonPath.<List<Integer>>read(history, "$.items[*].effectiveTotal"))
        .containsExactly(2);

    // A dept admin cannot adjust; after 10:30 nobody can.
    Cookie dept = signInReady(Demo.DEPT_ADMIN_PRODUCTION);
    expect(
        403,
        dept,
        HttpMethod.POST,
        "/api/admin/adjustments",
        add.formatted(TUE, true, Demo.EMPLOYEE, itemA(TUE)));
    setVnTime(TUE, "10:30");
    hc = signInReady(Demo.HC_ADMIN);
    String closed =
        expect(
            409,
            hc,
            HttpMethod.POST,
            "/api/admin/adjustments",
            add.formatted(TUE, true, Demo.EMPLOYEE, itemA(TUE)));
    assertThat(JsonPath.<String>read(closed, "$.code")).isEqualTo("ADJUSTMENT_CLOSED");
    assertThat(finalCountTotal(TUE)).isEqualTo(21);
  }

  @Test
  @Order(9)
  @DisplayName("BR-32 (v1.4): a check-in that reaches DMP after finalization is a case to review")
  void lateCheckIn() throws Exception {
    // Still before 10:30: the sync only records (the clock goes back for this check).
    tickAt(service("late-record"), TUE, "10:20");
    setVnTime(TUE, "10:25");
    Cookie hc = signInReady(Demo.HC_ADMIN);
    String view = read(hc, "/api/admin/reconciliation?date=" + TUE);
    assertThat(state(AFFECTED, TUE)).isEqualTo("ABSENT");
    assertThat(
            JsonPath.<List<String>>read(
                view, "$.exceptions[?(@.employeeCode == '" + code(12) + "')].cases[*]"))
        .containsExactly("CANCELLED_ABSENT", "LATE_CHECK_IN");
  }

  @Test
  @Order(10)
  @DisplayName(
      "BR-43: the HC admin is reminded from 08:45; a day not finalized by 12:00 is finalized by the"
          + " system")
  void reminderAndAutoFinalize() throws Exception {
    AttendanceService onTime = service("on-time");
    tickAt(onTime, WED, "07:30");
    tickAt(onTime, WED, "08:10");
    setVnTime(WED, "08:44");
    Cookie hc = signInReady(Demo.HC_ADMIN);
    Cookie employee = signInReady(Demo.EMPLOYEE);
    assertThat(read(hc, "/api/me/banners")).doesNotContain("DAY_NOT_FINALIZED");
    setVnTime(WED, "08:45");
    assertThat(read(hc, "/api/me/banners")).contains("DAY_NOT_FINALIZED");
    assertThat(read(employee, "/api/me/banners")).doesNotContain("DAY_NOT_FINALIZED");

    setVnTime(WED, "11:59");
    assertThat(jobs.closeDay()).isZero();
    setVnTime(WED, "12:00");
    assertThat(jobs.closeDay()).isEqualTo(1);
    DayCloseStore.Closed closed = dayClose.closed(factoryId, LocalDate.parse(WED));
    assertThat(closed).isNotNull();
    assertThat(closed.autoFinalized()).isTrue();
    assertThat(closed.finalizedById()).isNull();
    assertThat(finalCountTotal(WED)).isEqualTo(34);
    assertThat(read(hc, "/api/me/banners")).doesNotContain("DAY_NOT_FINALIZED");
  }

  @Test
  @Order(11)
  @DisplayName("BR-34 / BR-43: after a held cutoff the HC admin may finalize; every meal counts")
  void heldCutoff() throws Exception {
    AttendanceService error = service("api-error");
    tickAt(error, THU, "07:30");
    tickAt(error, THU, "08:10");
    setVnTime(THU, "08:30");
    Cookie hc = signInReady(Demo.HC_ADMIN);
    String view = read(hc, "/api/admin/reconciliation?date=" + THU);
    assertThat(JsonPath.<Boolean>read(view, "$.cutoffHeld")).isTrue();
    String res =
        expect(
            200,
            hc,
            HttpMethod.POST,
            "/api/admin/reconciliation/finalize",
            "{'date':'" + THU + "'}");
    assertThat(JsonPath.<String>read(res, "$.cutoffOutcome")).isEqualTo("HELD_SYNC_FAILED");
    assertThat(JsonPath.<Integer>read(res, "$.meals")).isEqualTo(34);
    assertThat(JsonPath.<Integer>read(res, "$.cancelled")).isZero();
  }

  @Test
  @Order(12)
  @DisplayName(
      "Rule 5: reconciliation, finalization, files and adjustments answer 403 out of scope")
  void forbidden() throws Exception {
    setVnTime(THU, "09:00");
    Cookie employee = signInReady(Demo.EMPLOYEE);
    Cookie sys = signInReady(Demo.SYS_ADMIN);
    Cookie dept = signInReady(Demo.DEPT_ADMIN_PRODUCTION);
    Cookie jewelry = signInReady(Demo.DEPT_ADMIN_JEWELRY);
    String adjustmentBody =
        "{'date':'%s','reason':'x','catererConfirmed':true,'lines':[{'employeeCode':'%s','menuItemId':'%s','delta':1}]}"
            .formatted(THU, Demo.EMPLOYEE, itemA(THU));
    List<Endpoint> hcOnly =
        List.of(
            Endpoint.of(
                HttpMethod.POST, "/api/admin/reconciliation/finalize", "{'date':'" + THU + "'}"),
            Endpoint.get("/api/admin/reconciliation/export?date=" + MON),
            Endpoint.get("/api/admin/reconciliation/forecast?weekStart=" + WEEK),
            Endpoint.get("/api/admin/reconciliation/forecast/export?weekStart=" + WEEK),
            Endpoint.get("/api/admin/adjustments?date=" + TUE),
            Endpoint.of(HttpMethod.POST, "/api/admin/adjustments", adjustmentBody),
            Endpoint.get("/api/admin/adjustments/employees/" + Demo.EMPLOYEE + "?date=" + TUE));
    List<Endpoint> all = new ArrayList<>(hcOnly);
    all.add(Endpoint.get("/api/admin/reconciliation?date=" + MON));
    expectForbidden(List.of(employee, sys), all);
    expectForbidden(List.of(dept, jewelry), hcOnly);
    // An adjustment of another factory's id is not found, never served.
    Cookie hc = signInReady(Demo.HC_ADMIN);
    expect(404, hc, HttpMethod.GET, "/api/admin/adjustments/nope/export", null);
  }
}
