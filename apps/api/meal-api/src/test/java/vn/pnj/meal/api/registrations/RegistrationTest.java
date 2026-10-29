package vn.pnj.meal.api.registrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
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
import vn.pnj.meal.api.dmp.DmpClient;
import vn.pnj.meal.api.dmp.MockDmpClient;
import vn.pnj.meal.api.jobs.Jobs;
import vn.pnj.meal.api.org.MealUnits;
import vn.pnj.meal.api.org.OrgSyncService;
import vn.pnj.meal.api.seed.SeedEmployees.Demo;
import vn.pnj.meal.api.settings.SettingsService;
import vn.pnj.meal.api.support.IntegrationTest;

/**
 * Port of apps/api-legacy/test/registration.e2e-spec.ts. P2 exit criteria with fake time, plus
 * BR-14 (lock-time cancellation), BR-20 to BR-27, BR-36 and a 403 for every admin endpoint.
 *
 * <p>Week N starts Monday 2027-10-04. Its regular closeAt is Friday 2027-10-01 00:00 Vietnam time
 * (shown as Thursday 23:59). Week N+1 (2027-10-11) proves the self-healing snapshot; week N+2
 * (2027-10-18) holds the 50 × 5 bulk-grid entry.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RegistrationTest extends IntegrationTest {
  private static final LocalDate N_DATE = LocalDate.of(2027, 10, 4);
  private static final String N = N_DATE.toString();
  private static final String N1 = N_DATE.plusDays(7).toString();
  private static final String N2 = N_DATE.plusDays(14).toString();
  private static final LocalDate PREV = N_DATE.minusDays(7); // week N-1
  private static final String MON = N;
  private static final String TUE = N_DATE.plusDays(1).toString();
  private static final String WED = N_DATE.plusDays(2).toString();
  private static final String THU = N_DATE.plusDays(3).toString();
  private static final String FRI = N_DATE.plusDays(4).toString();

  /** Nhóm Nhẫn 1, in Tổ Nhẫn (meal unit), in dept admin production's scope. */
  private static final String UNIT = "ou_XCT-A1-1";

  private static final String[][] DISHES = {
    {"chay", "Đậu hũ kho nấm", "CHAY"},
    {"chao", "Cháo thịt bằm", "CHAO"},
    {"ca", "Cá basa kho", "MAN"},
    {"thit", "Thịt luộc mắm nêm", "MAN"}
  };

  @Autowired Jobs jobs;
  @Autowired JsonMapper jsonMapper;
  @Autowired TransactionTemplate tx;
  @Autowired AuditService audit;
  @Autowired SystemAdminBootstrap bootstrap;
  @Autowired SettingsService settings;
  @Autowired MealUnits mealUnits;
  @Autowired ForecastService forecastService;

  private final Map<String, String> dishId = new HashMap<>();
  private String dessertId;

  /** Not on any menu at first; replaces a dish after closeAt (BR-14). */
  private String gaId;

  /** Menu item ids by week, date and dish key, read once from the employee's view. */
  private final Map<String, Map<String, Map<String, String>>> items = new HashMap<>();

  private String b; // a colleague with no forecast
  private String c; // a colleague who leaves (BR-36)
  private List<String> gridCodes;
  private List<Map<String, Object>> forecast;

  private String[] vnNow = {PREV.toString(), "10:00"};

  private Cookie hc;
  private Cookie a; // Demo.EMPLOYEE: registers before closeAt
  private Cookie bAgent;
  private Cookie cAgent;
  private Cookie dept; // dept admin of Xưởng Chế tác
  private Cookie deptOther; // dept admin of Xưởng Kim hoàn: out of scope
  private Cookie unassigned;
  private Cookie sys;

  /** Runs once the seed is in (the base class seeds before each test). */
  private void colleagues() {
    // Two plain colleagues of Demo.EMPLOYEE in the same team and meal unit.
    List<String> codes =
        jdbc.sql(
                """
                SELECT e."employeeCode" FROM "Employee" e
                WHERE e."orgUnitId" = :unit AND e.status = 'ACTIVE'
                  AND e."employeeCode" NOT LIKE 'PNJP00000%'
                  AND NOT EXISTS (SELECT 1 FROM "RoleAssignment" r WHERE r."employeeId" = e.id)
                ORDER BY e."employeeCode" LIMIT 2
                """)
            .param("unit", UNIT)
            .query(String.class)
            .list();
    b = codes.get(0);
    c = codes.get(1);
  }

  @AfterAll
  void cleanUp() {
    clock.clearFakeTime();
    // The plain mock DMP brings the leaver back for the other test classes.
    jobs.syncOrgEmployees();
  }

  @BeforeEach
  void atNow() throws Exception {
    if (b == null) {
      colleagues();
    }
    setVnTime(vnNow[0], vnNow[1]);
    hc = signInReady(Demo.HC_ADMIN);
    a = signInReady(Demo.EMPLOYEE);
    bAgent = signInReady(b);
    cAgent = signInReady(c);
    dept = signInReady(Demo.DEPT_ADMIN_PRODUCTION);
    deptOther = signInReady(Demo.DEPT_ADMIN_JEWELRY);
    unassigned = signInReady(Demo.UNASSIGNED);
    sys = signInReady(Demo.SYS_ADMIN);
  }

  /** Moves the fake clock and signs everyone in again (sessions age with fake time). */
  private void moveTo(String date, String time) throws Exception {
    vnNow = new String[] {date, time};
    atNow();
  }

  // --- helpers ----------------------------------------------------------------------------------

  private static String plus(String date, int days) {
    return LocalDate.parse(date).plusDays(days).toString();
  }

  private void publishWeek(String weekStart) throws Exception {
    List<String> days = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      List<String> its = new ArrayList<>();
      for (String[] d : DISHES) {
        its.add("{'dishId':'" + dishId.get(d[0]) + "'}");
      }
      days.add(
          "{'date':'%s','isServing':true,'items':[%s],'dessertIds':['%s']}"
              .formatted(plus(weekStart, i), String.join(",", its), dessertId));
    }
    String saved =
        expect(
            200,
            hc,
            HttpMethod.PUT,
            "/api/admin/menu/weeks",
            "{'canteenId':'ct_gv','weekStart':'%s','days':[%s]}"
                .formatted(weekStart, String.join(",", days)));
    String id = JsonPath.read(saved, "$.menu.id");
    expect(200, hc, HttpMethod.POST, "/api/admin/menu/weeks/" + id + "/publish", null);
  }

  private String myWeek(Cookie who, String weekStart) throws Exception {
    return read(who, "/api/registrations/me/week?weekStart=" + weekStart);
  }

  private void loadItems(String weekStart) throws Exception {
    String view = myWeek(a, weekStart);
    assertThat(JsonPath.<String>read(view, "$.state")).isEqualTo("PUBLISHED");
    Map<String, Map<String, String>> byDate = new HashMap<>();
    List<Map<String, Object>> days = JsonPath.read(view, "$.days");
    for (Map<String, Object> day : days) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> dayItems = (List<Map<String, Object>>) day.get("items");
      Map<String, String> ids = new HashMap<>();
      for (String[] d : DISHES) {
        ids.put(
            d[0],
            (String)
                dayItems.stream()
                    .filter(i -> ((String) i.get("displayName")).endsWith(d[1]))
                    .findFirst()
                    .orElseThrow()
                    .get("menuItemId"));
      }
      byDate.put((String) day.get("date"), ids);
    }
    items.put(weekStart, byDate);
  }

  private String item(String weekStart, String date, String key) {
    return items.get(weekStart).get(date).get(key);
  }

  private String line(String weekStart, String date, String key, Object quantity) {
    return "{'menuItemId':'%s','quantity':%s}".formatted(item(weekStart, date, key), quantity);
  }

  private static String lines(String... lines) {
    return "{'lines':[" + String.join(",", lines) + "]}";
  }

  private String saveDay(Cookie who, String date, String body, int status) throws Exception {
    return expect(status, who, HttpMethod.PUT, "/api/registrations/me/days/" + date, body);
  }

  /** The day view of /today for a date that has a menu. */
  private String dayOf(Cookie who, String date) throws Exception {
    String view = read(who, "/api/registrations/me/day?date=" + date);
    assertThat(JsonPath.<String>read(view, "$.state")).isEqualTo("DAY");
    return view;
  }

  private <T> T at(String body, String path) {
    return JsonPath.read(body, path);
  }

  private List<Map<String, Object>> forecastSnapshot(String weekStart) {
    return jdbc.sql(
            """
            SELECT f.id, f."employeeId", f.date::text AS date, f."menuItemId", f.quantity,
                   f."mealUnitId", f."floorId", f."canteenId", f."costCenterId"
            FROM "ForecastLine" f JOIN "MenuWeek" w ON w.id = f."menuWeekId"
            WHERE w."canteenId" = 'ct_gv' AND w."weekStart" = :weekStart
            ORDER BY f."employeeId", f.date, f."menuItemId", f.id
            """)
        .param("weekStart", LocalDate.parse(weekStart))
        .query()
        .listOfRows();
  }

  private int count(String sql, Map<String, Object> params) {
    return jdbc.sql(sql).params(params).query(Integer.class).single();
  }

  private String gridUrl(String weekStart) {
    return "/api/admin/registrations/units/" + UNIT + "/grid?weekStart=" + weekStart;
  }

  // --- BR-02 ------------------------------------------------------------------------------------

  @Test
  @Order(1)
  @DisplayName("sets up the dish library")
  void library() throws Exception {
    for (String[] d : DISHES) {
      dishId.put(d[0], createDish(d[1], d[2]));
    }
    gaId = createDish("Gà kho gừng", "MAN");
    dessertId =
        JsonPath.read(
            expect(
                201,
                hc,
                HttpMethod.POST,
                "/api/admin/desserts",
                "{'canteenId':'ct_gv','name':'Chuối cau'}"),
            "$.id");
  }

  private String createDish(String name, String type) throws Exception {
    return JsonPath.read(
        expect(
            201,
            hc,
            HttpMethod.POST,
            "/api/admin/dishes",
            "{'canteenId':'ct_gv','name':'%s','typeId':'dt_fac_gv_%s','sessions':['LUNCH']}"
                .formatted(name, type)),
        "$.id");
  }

  @Test
  @Order(2)
  @DisplayName("BR-02: the week reads \"not published\" and a save is refused")
  void notPublished() throws Exception {
    assertThat(at(myWeek(a, N), "$.state").toString()).isEqualTo("NOT_PUBLISHED");
    expect(
        409,
        a,
        HttpMethod.PUT,
        "/api/registrations/me/week",
        "{'weekStart':'" + N + "','days':[{'date':'" + MON + "','lines':[]}]}");
  }

  @Test
  @Order(3)
  @DisplayName("BR-60: an employee without a meal unit cannot register")
  void unassignedEmployee() throws Exception {
    assertThat(myWeek(unassigned, N))
        .isEqualTo("{\"state\":\"UNASSIGNED\",\"weekStart\":\"" + N + "\"}");
  }

  @Test
  @Order(4)
  @DisplayName("publishes weeks N, N+1 and N+2; the open week is preselected (J1)")
  void publish() throws Exception {
    publishWeek(N);
    publishWeek(N1);
    publishWeek(N2);
    loadItems(N);
    loadItems(N1);
    String res = read(a, "/api/registrations/me/week");
    assertThat(at(res, "$.state").toString()).isEqualTo("PUBLISHED");
    assertThat(at(res, "$.weekStart").toString()).isEqualTo(N);
  }

  // --- P2 exit criterion: the bulk grid
  // ------------------------------------------------------------

  @Test
  @Order(10)
  @DisplayName("the dept admin lists their units and opens the grid of a team")
  void gridOpens() throws Exception {
    String units = read(dept, "/api/admin/registrations/units");
    List<Integer> counts = at(units, "$.items[?(@.id == '" + UNIT + "')].employeeCount");
    assertThat(counts.getFirst()).isGreaterThanOrEqualTo(53);
    // Nothing outside Xưởng Chế tác is offered.
    assertThat(at(units, "$.items[?(@.id == 'ou_XKH')]").toString()).isEqualTo("[]");

    String grid = read(dept, gridUrl(N2));
    assertThat(at(grid, "$.state").toString()).isEqualTo("PUBLISHED");
    assertThat(at(grid, "$.canteenId").toString()).isEqualTo("ct_gv");
    assertThat(JsonPath.<List<String>>read(grid, "$.days[*].window"))
        .containsExactly("ON_BEHALF", "ON_BEHALF", "ON_BEHALF", "ON_BEHALF", "ON_BEHALF");
    gridCodes =
        JsonPath.<List<String>>read(grid, "$.rows[*].employeeCode").stream()
            .filter(code -> !List.of(Demo.EMPLOYEE, b, c).contains(code))
            .limit(50)
            .toList();
    assertThat(gridCodes).hasSize(50);
  }

  @Test
  @Order(11)
  @DisplayName("saves 5 days of 50 rows, one batch per day")
  void gridSaves() throws Exception {
    String grid = read(dept, gridUrl(N2));
    long started = System.nanoTime();
    for (int i = 0; i < 5; i++) {
      String date = at(grid, "$.days[" + i + "].date");
      String chay = at(grid, "$.days[" + i + "].items[0].id");
      String ca = at(grid, "$.days[" + i + "].items[2].id");
      List<String> rows = new ArrayList<>();
      for (int n = 0; n < gridCodes.size(); n++) {
        rows.add(
            "{'employeeCode':'%s','lines':[{'menuItemId':'%s','quantity':1},{'menuItemId':'%s','quantity':%d}]}"
                .formatted(gridCodes.get(n), chay, ca, (n + i) % 3));
      }
      String res =
          expect(
              200,
              dept,
              HttpMethod.PUT,
              "/api/admin/registrations/units/" + UNIT + "/days/" + date,
              "{'canteenId':'ct_gv','rows':[" + String.join(",", rows) + "]}");
      assertThat(JsonPath.<Integer>read(res, "$.changedEmployees")).isEqualTo(50);
    }
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));

    String after = read(dept, gridUrl(N2));
    for (String code : gridCodes) {
      for (int i = 0; i < 5; i++) {
        String date = at(after, "$.days[" + i + "].date");
        String chay = at(after, "$.days[" + i + "].items[0].id");
        List<Integer> q =
            at(
                after,
                "$.rows[?(@.employeeCode == '%s')].cells['%s'].quantities['%s']"
                    .formatted(code, date, chay));
        assertThat(q).containsExactly(1);
      }
    }
    // 50 × 5 Chay lines, plus the Mặn lines whose quantity was not 0.
    int man = 0;
    for (int i = 0; i < 5; i++) {
      for (int n = 0; n < 50; n++) {
        if ((n + i) % 3 != 0) {
          man++;
        }
      }
    }
    assertThat(
            count(
                """
                SELECT count(*) FROM "Registration" r JOIN "Employee" e ON e.id = r."employeeId"
                WHERE e."employeeCode" IN (:codes) AND r.date BETWEEN :from AND :to
                  AND r.quantity > 0
                """,
                Map.of(
                    "codes",
                    gridCodes,
                    "from",
                    LocalDate.parse(N2),
                    "to",
                    LocalDate.parse(N2).plusDays(4))))
        .isEqualTo(250 + man);
    // Every changed employee-day is audited (BR-70).
    assertThat(
            count(
                """
                SELECT count(*) FROM "AuditLog"
                WHERE action = 'registration.onBehalf' AND "entityId" LIKE :suffix
                """,
                Map.of("suffix", "%:" + N2)))
        .isEqualTo(50);
  }

  @Test
  @Order(12)
  @DisplayName("one invalid row rejects the whole batch with row-level errors")
  void gridRejects() throws Exception {
    String grid = read(dept, gridUrl(N2));
    String date = at(grid, "$.days[0].date");
    String item0 = at(grid, "$.days[0].items[0].id");
    String item1 = at(grid, "$.days[0].items[1].id");
    String snapshotSql =
        "SELECT id, quantity, status::text FROM \"Registration\" WHERE date = :d ORDER BY id";
    List<Map<String, Object>> before =
        jdbc.sql(snapshotSql).param("d", LocalDate.parse(date)).query().listOfRows();
    String outsider =
        jdbc.sql(
                "SELECT \"employeeCode\" FROM \"Employee\" WHERE \"orgUnitId\" = 'ou_XKH-DUC-2' LIMIT 1")
            .query(String.class)
            .single();
    String res =
        expect(
            400,
            dept,
            HttpMethod.PUT,
            "/api/admin/registrations/units/" + UNIT + "/days/" + date,
            ("{'canteenId':'ct_gv','rows':["
                    + "{'employeeCode':'%s','lines':[{'menuItemId':'%s','quantity':5}]},"
                    + "{'employeeCode':'%s','lines':[{'menuItemId':'%s','quantity':-1}]},"
                    + "{'employeeCode':'%s','lines':[{'menuItemId':'%s','quantity':1.5}]},"
                    + "{'employeeCode':'%s','lines':[]}]}")
                .formatted(
                    gridCodes.get(0),
                    item0,
                    gridCodes.get(1),
                    item0,
                    gridCodes.get(2),
                    item1,
                    outsider));
    assertThat(JsonPath.<List<Map<String, Object>>>read(res, "$.details"))
        .containsExactly(
            Map.of(
                "employeeCode", gridCodes.get(1), "code", "QUANTITY_NEGATIVE", "menuItemId", item0),
            Map.of(
                "employeeCode",
                gridCodes.get(2),
                "code",
                "QUANTITY_NOT_INTEGER",
                "menuItemId",
                item1),
            Map.of("employeeCode", outsider, "code", "NOT_IN_UNIT"));
    assertThat(jdbc.sql(snapshotSql).param("d", LocalDate.parse(date)).query().listOfRows())
        .isEqualTo(before);
  }

  @Test
  @Order(13)
  @DisplayName("BR-21: progress by unit and the not-registered list")
  void progress() throws Exception {
    String progress = read(dept, "/api/admin/registrations/progress?weekStart=" + N2);
    Map<String, Object> unit =
        JsonPath.<List<Map<String, Object>>>read(
                progress, "$.units[?(@.mealUnitId == 'ou_XCT-A1')]")
            .getFirst();
    assertThat(unit).containsEntry("registered", 50).containsEntry("published", true);
    assertThat((int) unit.get("headcount"))
        .isEqualTo((int) unit.get("registered") + (int) unit.get("notRegistered"));

    String list = read(dept, "/api/admin/registrations/not-registered?weekStart=" + N2);
    List<String> codes = at(list, "$.items[*].employeeCode");
    assertThat(codes).contains(Demo.EMPLOYEE).doesNotContainAnyElementsOf(gridCodes);
    // Dept admins only see their own units (plan section 3).
    assertThat(JsonPath.<List<String>>read(list, "$.items[*].orgPath"))
        .allMatch(p -> p.contains("Xưởng Chế tác"));
    String day =
        read(hc, "/api/admin/registrations/not-registered?weekStart=" + N2 + "&date=" + N2);
    assertThat(JsonPath.<Integer>read(day, "$.total"))
        .isGreaterThan(JsonPath.<Integer>read(list, "$.total"));
  }

  // --- P2 exit criterion: register, free edits, forecast, BR-23 changes, lock -------------------

  @Test
  @Order(20)
  @DisplayName("BR-22: on Tuesday of week N-1 the employee registers the week")
  void registers() throws Exception {
    moveTo(PREV.plusDays(1).toString(), "10:00");
    String res =
        expect(
            200,
            a,
            HttpMethod.PUT,
            "/api/registrations/me/week",
            "{'weekStart':'%s','days':[{'date':'%s','lines':[%s]},{'date':'%s','lines':[%s,%s]},{'date':'%s','lines':[%s]}]}"
                .formatted(
                    N,
                    MON,
                    line(N, MON, "ca", 1),
                    TUE,
                    line(N, TUE, "chay", 1),
                    line(N, TUE, "chao", 1),
                    WED,
                    line(N, WED, "thit", 2)));
    assertThat(JsonPath.<List<String>>read(res, "$.changedDays")).containsExactly(MON, TUE, WED);
    assertThat(at(res, "$.view.state").toString()).isEqualTo("PUBLISHED");
    assertThat(JsonPath.<Integer>read(res, "$.view.totalMeals")).isEqualTo(5);
    assertThat(JsonPath.<Boolean>read(res, "$.view.forecastTaken")).isFalse();
    assertThat(JsonPath.<List<String>>read(res, "$.view.days[*].window"))
        .containsExactly("OPEN", "OPEN", "OPEN", "OPEN", "OPEN");
    // C registers too, before closeAt (used by BR-36 below).
    for (String date : List.of(WED, THU, FRI)) {
      saveDay(cAgent, date, lines(line(N, date, "ca", 1)), 200);
    }
  }

  @Test
  @Order(21)
  @DisplayName("BR-20: the API rejects negative, fractional and off-menu quantities")
  void rejectsBadQuantities() throws Exception {
    moveTo(PREV.plusDays(1).toString(), "10:00");
    String res =
        expect(
            400,
            a,
            HttpMethod.PUT,
            "/api/registrations/me/week",
            "{'weekStart':'%s','days':[{'date':'%s','lines':[%s]},{'date':'%s','lines':[%s]},{'date':'%s','lines':[%s]}]}"
                .formatted(
                    N,
                    MON,
                    line(N, MON, "ca", -1),
                    TUE,
                    line(N, TUE, "chay", 1.5),
                    WED,
                    line(N, MON, "chay", 1)));
    assertThat(JsonPath.<List<String>>read(res, "$.details[*].code"))
        .containsExactly("QUANTITY_NEGATIVE", "QUANTITY_NOT_INTEGER", "ITEM_NOT_ON_MENU");
    assertThat(JsonPath.<String>read(res, "$.details[0].date")).isEqualTo(MON);
    // No portion limit: 3 of one dish is fine (BR-20).
    saveDay(a, THU, lines(line(N, THU, "ca", 3)), 200);
  }

  @Test
  @Order(22)
  @DisplayName("BR-22: free edits before closeAt are not audited as changes after close")
  void freeEdits() throws Exception {
    moveTo(PREV.plusDays(2).toString(), "15:00");
    saveDay(a, WED, lines(line(N, WED, "thit", 1), line(N, WED, "ca", 1)), 200);
    saveDay(a, TUE, lines(line(N, TUE, "chay", 1)), 200);
    assertThat(
            count(
                """
                SELECT count(*) FROM "AuditLog"
                WHERE action = 'registration.changedAfterClose' AND "entityId" LIKE :prefix
                """,
                Map.of("prefix", Demo.EMPLOYEE + ":%")))
        .isZero();
  }

  @Test
  @Order(23)
  @DisplayName("BR-03: a save at Thursday 23:59:30 is still before closeAt")
  void lastMinute() throws Exception {
    moveTo(PREV.plusDays(3).toString(), "23:59");
    clock.advanceFakeTime(Duration.ofSeconds(30));
    String res = saveDay(a, THU, lines(line(N, THU, "ca", 1)), 200);
    assertThat(at(res, "$.view.day.window").toString()).isEqualTo("OPEN");
  }

  @Test
  @Order(24)
  @DisplayName("BR-07: at Friday 00:00 the close-week job takes the forecast snapshot, once")
  void snapshot() throws Exception {
    moveTo(PREV.plusDays(4).toString(), "00:00");
    assertThat(jobs.closeWeek()).isGreaterThanOrEqualTo(1);
    assertThat(jobs.closeWeek()).isZero();

    Map<String, Object> week =
        jdbc.sql(
                """
                SELECT id, status::text FROM "MenuWeek"
                WHERE "canteenId" = 'ct_gv' AND "weekStart" = :w
                """)
            .param("w", N_DATE)
            .query()
            .singleRow();
    assertThat(week.get("status")).isEqualTo("CLOSED");
    forecast = forecastSnapshot(N);
    String employeeA = employeeId(Demo.EMPLOYEE);
    List<Map<String, Object>> mine =
        forecast.stream().filter(l -> l.get("employeeId").equals(employeeA)).toList();
    // Mon 1 Cá, Tue 1 Chay, Wed 1 Thịt + 1 Cá, Thu 1 Cá.
    assertThat(mine)
        .extracting(l -> l.get("date") + " " + l.get("quantity"))
        .containsExactlyInAnyOrder(MON + " 1", TUE + " 1", WED + " 1", WED + " 1", THU + " 1");
    // BR-64: the lines carry the mapping in effect at closeAt.
    assertThat(mine.getFirst())
        .containsEntry("mealUnitId", "ou_XCT-A1")
        .containsEntry("floorId", "fl_T2")
        .containsEntry("canteenId", "ct_gv");
    String employeeB = employeeId(b);
    assertThat(forecast).noneMatch(l -> l.get("employeeId").equals(employeeB));
    assertThat(
            count(
                "SELECT count(*) FROM \"AuditLog\" WHERE action = 'forecast.snapshot' AND \"entityId\" = :id",
                Map.of("id", week.get("id"))))
        .isEqualTo(1);
  }

  @Test
  @Order(25)
  @DisplayName(
      "BR-23: after closeAt the employee adds a meal for Monday; it is audited and differs from the"
          + " forecast")
  void afterClose() throws Exception {
    moveTo(PREV.plusDays(5).toString(), "10:00");
    String res = saveDay(a, MON, lines(line(N, MON, "ca", 1), line(N, MON, "chay", 1)), 200);
    Map<String, Object> day = at(res, "$.view.day");
    assertThat(day)
        .containsEntry("window", "AFTER_CLOSE")
        .containsEntry("forecastTotal", 1)
        .containsEntry("actualTotal", 2)
        .containsEntry("changed", true);
    String after =
        jdbc.sql(
                """
                SELECT after::text FROM "AuditLog"
                WHERE action = 'registration.changedAfterClose' AND "entityId" = :id
                """)
            .param("id", Demo.EMPLOYEE + ":" + MON)
            .query(String.class)
            .single();
    assertThat(JsonPath.<Map<String, Object>>read(after, "$.quantities")).isNotEmpty();
  }

  @Test
  @Order(26)
  @DisplayName("BR-23: an employee with no forecast registers for Monday at 07:59 on day D")
  void noForecast() throws Exception {
    moveTo(MON, "07:59");
    String res = saveDay(bAgent, MON, lines(line(N, MON, "thit", 1)), 200);
    Map<String, Object> day = at(res, "$.view.day");
    assertThat(day)
        .containsEntry("window", "AFTER_CLOSE")
        .containsEntry("forecastTotal", 0)
        .containsEntry("actualTotal", 1)
        .containsEntry("changed", true);
  }

  @Test
  @Order(27)
  @DisplayName("BR-24: at 08:00 on day D employees cannot change anything; later days stay open")
  void locked() throws Exception {
    moveTo(MON, "08:00");
    String locked = saveDay(a, MON, lines(), 409);
    assertThat(at(locked, "$.code").toString()).isEqualTo("DAY_LOCKED");
    assertThat(at(locked, "$.details.date").toString()).isEqualTo(MON);
    saveDay(bAgent, MON, lines(), 409);
    assertThat(at(dayOf(a, MON), "$.day.window").toString()).isEqualTo("LOCKED");
    // A week save that leaves the locked day as it is still goes through.
    expect(
        200,
        a,
        HttpMethod.PUT,
        "/api/registrations/me/week",
        "{'weekStart':'%s','days':[{'date':'%s','lines':[%s,%s]},{'date':'%s','lines':[%s]}]}"
            .formatted(
                N,
                MON,
                line(N, MON, "ca", 1),
                line(N, MON, "chay", 1),
                FRI,
                line(N, FRI, "chao", 1)));
    // Admins cannot register on behalf after the lock either (BR-21).
    assertThat(at(read(dept, gridUrl(N)), "$.days[0].window").toString()).isEqualTo("EXTRA");
    expect(
        409,
        dept,
        HttpMethod.PUT,
        "/api/admin/registrations/units/" + UNIT + "/days/" + MON,
        "{'canteenId':'ct_gv','rows':[{'employeeCode':'" + b + "','lines':[]}]}");
  }

  private static int sum(Map<String, Integer> quantities) {
    return quantities == null ? 0 : quantities.values().stream().mapToInt(Integer::intValue).sum();
  }

  @Test
  @Order(28)
  @DisplayName(
      "both changes show as differences from the forecast, in the employee views and the grid")
  void differences() throws Exception {
    moveTo(MON, "08:00");
    Map<String, Object> dayA = at(dayOf(a, MON), "$.day");
    assertThat(dayA)
        .containsEntry("changed", true)
        .containsEntry("forecastTotal", 1)
        .containsEntry("actualTotal", 2);
    Map<String, Object> dayB = at(dayOf(bAgent, MON), "$.day");
    assertThat(dayB)
        .containsEntry("changed", true)
        .containsEntry("forecastTotal", 0)
        .containsEntry("actualTotal", 1);
    String grid = read(dept, gridUrl(N));
    assertThat(JsonPath.<Boolean>read(grid, "$.forecastTaken")).isTrue();
    String cellA = "$.rows[?(@.employeeCode == '" + Demo.EMPLOYEE + "')].cells['" + MON + "']";
    String cellB = "$.rows[?(@.employeeCode == '" + b + "')].cells['" + MON + "']";
    assertThat(
            List.of(
                sum(JsonPath.<List<Map<String, Integer>>>read(grid, cellA + ".forecast").get(0)),
                sum(JsonPath.<List<Map<String, Integer>>>read(grid, cellA + ".quantities").get(0))))
        .containsExactly(1, 2);
    assertThat(
            List.of(
                sum(JsonPath.<List<Map<String, Integer>>>read(grid, cellB + ".forecast").get(0)),
                sum(JsonPath.<List<Map<String, Integer>>>read(grid, cellB + ".quantities").get(0))))
        .containsExactly(0, 1);
  }

  @Test
  @Order(29)
  @DisplayName("BR-25: after 08:00 an admin adds an Extra meal with a reason")
  void extra() throws Exception {
    moveTo(MON, "08:05");
    String chao = item(N, MON, "chao");
    String url = "/api/admin/registrations/employees/" + Demo.EMPLOYEE + "/extras";
    expect(
        400,
        dept,
        HttpMethod.POST,
        url,
        "{'date':'%s','menuItemId':'%s','quantity':1}".formatted(MON, chao));
    expect(
        400,
        dept,
        HttpMethod.POST,
        url,
        "{'date':'%s','menuItemId':'%s','quantity':1,'reason':' '}".formatted(MON, chao));
    expect(
        400,
        dept,
        HttpMethod.POST,
        url,
        "{'date':'%s','menuItemId':'%s','quantity':1.5,'reason':'x'}".formatted(MON, chao));
    String res =
        expect(
            200,
            dept,
            HttpMethod.POST,
            url,
            "{'date':'%s','menuItemId':'%s','quantity':1,'reason':'Quên đăng ký'}"
                .formatted(MON, chao));
    assertThat(JsonPath.<Integer>read(res, "$.before")).isZero();
    assertThat(JsonPath.<Integer>read(res, "$.after")).isEqualTo(1);
    Map<String, Object> day = at(dayOf(a, MON), "$.day");
    assertThat(day).containsEntry("extra", true).containsEntry("actualTotal", 3);
    assertThat(
            jdbc.sql(
                    """
                    SELECT reason FROM "AuditLog"
                    WHERE action = 'registration.extraAdded' AND "entityId" = :id
                    """)
                .param("id", Demo.EMPLOYEE + ":" + MON)
                .query(String.class)
                .single())
        .isEqualTo("Quên đăng ký");
    // Before a day's lock, extras are not the way: register on behalf (BR-21).
    expect(
        409,
        dept,
        HttpMethod.POST,
        url,
        "{'date':'%s','menuItemId':'%s','quantity':1,'reason':'x'}"
            .formatted(TUE, item(N, TUE, "chao")));
  }

  @Test
  @Order(30)
  @DisplayName("BR-27: colleagues see name, dish, quantity and eating only")
  void team() throws Exception {
    moveTo(MON, "08:05");
    String res = read(bAgent, "/api/team");
    assertThat(at(res, "$.unitId").toString()).isEqualTo("ou_XCT-A1");
    String employeeA = employeeId(Demo.EMPLOYEE);
    Map<String, Object> an =
        JsonPath.<List<Map<String, Object>>>read(res, "$.members[?(@.key == '" + employeeA + "')]")
            .getFirst();
    assertThat(an.keySet()).containsExactlyInAnyOrder("eating", "key", "meals", "name");
    assertThat(an).containsEntry("eating", true);
    assertThat(
            JsonPath.<List<Integer>>read(
                    res, "$.members[?(@.key == '" + employeeA + "')].meals[*].quantity")
                .stream()
                .mapToInt(Integer::intValue)
                .sum())
        .isEqualTo(3);
    String search = read(bAgent, "/api/team?q=" + Demo.EMPLOYEE);
    assertThat(JsonPath.<List<Object>>read(search, "$.members").size())
        .isLessThan(JsonPath.<List<Object>>read(res, "$.members").size());
    assertThat(JsonPath.<List<String>>read(search, "$.members[*].key")).contains(employeeA);
  }

  private String regStatus(String code, String date, String dishKey) {
    return jdbc.sql(
            """
            SELECT r.status::text FROM "Registration" r
            JOIN "Employee" e ON e.id = r."employeeId"
            JOIN "MenuItem" i ON i.id = r."menuItemId"
            WHERE e."employeeCode" = :code AND r.date = :date AND i."dishId" = :dish
            """)
        .param("code", code)
        .param("date", LocalDate.parse(date))
        .param("dish", dishId.get(dishKey))
        .query(String.class)
        .single();
  }

  private void dismissNotice(Cookie who, String type, String date) throws Exception {
    String banners = read(who, "/api/me/banners");
    List<String> ids =
        JsonPath.read(
            banners,
            "$.items[?(@.notificationType == '%s' && @.params.date == '%s')].notificationId"
                .formatted(type, date));
    assertThat(ids).isNotEmpty();
    expect(200, who, HttpMethod.POST, "/api/me/notifications/" + ids.getFirst() + "/read", null);
  }

  @Test
  @Order(31)
  @DisplayName("BR-14: a removed dish waits for reselection, and is cancelled at that day's lock")
  void reselect() throws Exception {
    moveTo(TUE, "10:00");
    String menu = read(hc, "/api/admin/menu/weeks?canteenId=ct_gv&weekStart=" + N);
    List<Map<String, Object>> menuDays = at(menu, "$.days[?(@.isServing == true)]");
    List<String> days = new ArrayList<>();
    for (Map<String, Object> d : menuDays) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> its = (List<Map<String, Object>>) d.get("items");
      List<String> kept =
          its.stream()
              .filter(
                  i -> !d.get("date").equals(WED) || !i.get("dishId").equals(dishId.get("thit")))
              .map(i -> "{'id':'%s','dishId':'%s'}".formatted(i.get("id"), i.get("dishId")))
              .toList();
      days.add(
          "{'date':'%s','isServing':true,'items':[%s],'dessertIds':['%s']}"
              .formatted(d.get("date"), String.join(",", kept), dessertId));
    }
    String saved =
        expect(
            200,
            hc,
            HttpMethod.PUT,
            "/api/admin/menu/weeks",
            "{'canteenId':'ct_gv','weekStart':'%s','days':[%s],'reason':'NCC đổi món'}"
                .formatted(N, String.join(",", days)));
    assertThat(JsonPath.<Integer>read(saved, "$.effects.flaggedRegistrations")).isEqualTo(1);
    assertThat(JsonPath.<List<Map<String, Object>>>read(dayOf(a, WED), "$.day.needsReselect"))
        .containsExactly(Map.of("displayName", "[Mặn] Thịt luộc mắm nêm", "quantity", 1));
    // The employee was told in the app; dismissing the notice leaves no trace for other tests.
    dismissNotice(a, "MENU_DISH_REMOVED", WED);

    setVnTime(WED, "07:59");
    jobs.reselectDeadline();
    assertThat(regStatus(Demo.EMPLOYEE, WED, "thit")).isEqualTo("NEEDS_RESELECT");
    setVnTime(WED, "08:00");
    assertThat(jobs.reselectDeadline()).isGreaterThanOrEqualTo(1);
    assertThat(jobs.reselectDeadline()).isZero();
    assertThat(regStatus(Demo.EMPLOYEE, WED, "thit")).isEqualTo("CANCELLED");
    assertThat(
            count(
                """
                SELECT count(*) FROM "AuditLog"
                WHERE action = 'registration.cancelledUnselected' AND "actorId" IS NULL
                """,
                Map.of()))
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  @Order(32)
  @DisplayName(
      "BR-14: a dish replaced after closeAt shows a red note to the employees whose meal moved")
  void replaced() throws Exception {
    moveTo(TUE, "10:30");
    String menu = read(hc, "/api/admin/menu/weeks?canteenId=ct_gv&weekStart=" + N);
    List<Map<String, Object>> thuItems = at(menu, "$.days[?(@.date == '" + THU + "')].items[*]");
    List<String> its =
        thuItems.stream()
            .map(
                i ->
                    "{'id':'%s','dishId':'%s'}"
                        .formatted(
                            i.get("id"),
                            i.get("dishId").equals(dishId.get("ca")) ? gaId : i.get("dishId")))
            .toList();
    String saved =
        expect(
            200,
            hc,
            HttpMethod.PUT,
            "/api/admin/menu/weeks",
            "{'canteenId':'ct_gv','weekStart':'%s','reason':'NCC hết cá','days':[{'date':'%s','isServing':true,'items':[%s],'dessertIds':['%s']}]}"
                .formatted(N, THU, String.join(",", its), dessertId));
    assertThat(JsonPath.<Integer>read(saved, "$.effects.movedRegistrations"))
        .isGreaterThanOrEqualTo(1);

    String thursday = dayOf(a, THU);
    Map<String, Object> ga =
        JsonPath.<List<Map<String, Object>>>read(
                thursday, "$.day.items[?(@.displayName == '[Mặn] Gà kho gừng')]")
            .getFirst();
    assertThat(ga)
        .containsEntry("quantity", 1)
        .containsEntry("replacedAfterClose", Map.of("from", "[Mặn] Cá basa kho"));
    assertThat(
            JsonPath.<List<Object>>read(thursday, "$.day.items[?(@.replacedAfterClose != null)]"))
        .hasSize(1);
    // Someone who picks the new dish themselves gets no note.
    String gaItem = (String) ga.get("menuItemId");
    saveDay(bAgent, THU, "{'lines':[{'menuItemId':'" + gaItem + "','quantity':1}]}", 200);
    Map<String, Object> mine =
        JsonPath.<List<Map<String, Object>>>read(
                dayOf(bAgent, THU), "$.day.items[?(@.menuItemId == '" + gaItem + "')]")
            .getFirst();
    assertThat(mine).containsEntry("quantity", 1).containsEntry("replacedAfterClose", null);
    String banners = read(a, "/api/me/banners");
    Map<String, Object> params =
        JsonPath.<List<Map<String, Object>>>read(
                banners,
                "$.items[?(@.notificationType == 'MENU_DISH_REPLACED' && @.params.date == '"
                    + THU
                    + "')].params")
            .getFirst();
    assertThat(params)
        .containsEntry("from", "[Mặn] Cá basa kho")
        .containsEntry("to", "[Mặn] Gà kho gừng");
    dismissNotice(a, "MENU_DISH_REPLACED", THU);
  }

  /** The mock DMP, with one employee leaving on {@code endDate}. */
  private DmpClient leaving(String code, LocalDate endDate) {
    MockDmpClient base = new MockDmpClient("on-time", jsonMapper);
    return new DmpClient() {
      @Override
      public List<OrgNode> getOrgTree() {
        return base.getOrgTree();
      }

      @Override
      public List<Employee> getEmployees() {
        return base.getEmployees().stream()
            .map(
                e ->
                    e.employeeCode().equals(code)
                        ? new Employee(
                            e.employeeCode(),
                            e.name(),
                            e.email(),
                            e.orgUnitCode(),
                            e.costCenterCode(),
                            e.startDate(),
                            endDate)
                        : e)
            .toList();
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
  @Order(33)
  @DisplayName("BR-36: a leaver loses their meals from the end date on sync")
  void leaver() throws Exception {
    moveTo(WED, "09:00");
    OrgSyncService sync =
        new OrgSyncService(
            jdbc,
            tx,
            jsonMapper,
            clock,
            audit,
            bootstrap,
            settings,
            mealUnits,
            forecastService,
            leaving(c, LocalDate.parse(THU)));
    OrgSyncService.Result result = sync.run("fac_gv", null);
    assertThat(result.status()).isEqualTo("SUCCESS");
    assertThat(result.details().mealsCancelledForLeavers()).isEqualTo(2);
    assertThat(
            jdbc.sql(
                    """
                    SELECT r.date::text || ' ' || r.status::text FROM "Registration" r
                    JOIN "Employee" e ON e.id = r."employeeId"
                    WHERE e."employeeCode" = :code AND r.date >= :from ORDER BY r.date
                    """)
                .param("code", c)
                .param("from", N_DATE)
                .query(String.class)
                .list())
        .containsExactly(WED + " ACTIVE", THU + " CANCELLED", FRI + " CANCELLED");
    // The leaver can no longer register for those days.
    Cookie signedIn = signInReady(c);
    saveDay(signedIn, FRI, lines(line(N, FRI, "ca", 1)), 409);
  }

  @Test
  @Order(34)
  @DisplayName("/history shows past days with forecast, actual and status")
  void history() throws Exception {
    moveTo(WED, "12:00");
    String res = read(a, "/api/registrations/me/history?from=" + MON + "&to=" + WED);
    Map<String, Object> monday =
        JsonPath.<List<Map<String, Object>>>read(res, "$.items[?(@.date == '" + MON + "')]")
            .getFirst();
    assertThat(monday)
        .containsEntry("forecast", 1)
        .containsEntry("actual", 3)
        .containsEntry("status", "REGISTERED");
    assertThat(JsonPath.<List<String>>read(res, "$.items[*].date"))
        .isSortedAccordingTo((x, y) -> y.compareTo(x));
    expect(
        400, a, HttpMethod.GET, "/api/registrations/me/history?from=" + WED + "&to=" + MON, null);
  }

  @Test
  @Order(35)
  @DisplayName(
      "BR-07: the forecast snapshot is unchanged after every edit, extra, removal and"
          + " cancellation")
  void snapshotUnchanged() {
    assertThat(forecastSnapshot(N)).isEqualTo(forecast);
    assertThatThrownBy(
            () ->
                jdbc.sql("DELETE FROM \"ForecastLine\" WHERE id = :id")
                    .param("id", forecast.getFirst().get("id"))
                    .update())
        .isInstanceOf(DataAccessException.class);
  }

  // --- BR-07: self-healing
  // --------------------------------------------------------------------------

  @Test
  @Order(40)
  @DisplayName(
      "BR-07: without the job, the first request after closeAt takes the snapshot before any"
          + " change")
  void selfHealing() throws Exception {
    moveTo(WED, "13:00");
    saveDay(a, N1, lines(line(N1, N1, "ca", 1)), 200);
    // closeAt of week N+1 is Friday of week N, 00:00. No job runs.
    moveTo(FRI, "00:01");
    String res = saveDay(a, N1, lines(line(N1, N1, "ca", 2)), 200);
    Map<String, Object> day = at(res, "$.view.day");
    assertThat(day)
        .containsEntry("forecastTotal", 1)
        .containsEntry("actualTotal", 2)
        .containsEntry("changed", true);
    assertThat(forecastSnapshot(N1)).extracting(l -> l.get("quantity")).containsExactly(1);
  }

  // --- Khối / Trung tâm / Phòng ban filter
  // ---------------------------------------------------------

  @Test
  @Order(41)
  @DisplayName("lists only nodes in scope, and narrows the not-registered list to a node")
  void orgFilter() throws Exception {
    moveTo(FRI, "00:01");
    String options = read(dept, "/api/admin/registrations/org-units");
    assertThat(JsonPath.<List<String>>read(options, "$.items[*].id")).doesNotContain("ou_XKH");
    Map<String, Object> first = at(options, "$.items[0]");
    assertThat(first)
        .containsEntry("id", "ou_XCT")
        .containsEntry("levelName", "Phòng/Xưởng")
        .containsEntry("level", 3);
    String base = "/api/admin/registrations/not-registered?weekStart=" + N2;
    int all = JsonPath.read(read(hc, base), "$.total");
    int team = JsonPath.read(read(hc, base + "&orgUnitId=" + UNIT), "$.total");
    assertThat(team).isPositive().isLessThan(all);
    // A dept admin filtering on a node outside their scope gets nobody, never more.
    assertThat(JsonPath.<Integer>read(read(dept, base + "&orgUnitId=ou_XKH"), "$.total")).isZero();
  }

  // --- Excel import of a unit's week
  // ---------------------------------------------------------------

  private XSSFWorkbook template() throws Exception {
    MockHttpServletResponse res =
        send(
            dept,
            HttpMethod.GET,
            "/api/admin/registrations/units/" + UNIT + "/import-template?weekStart=" + N2,
            null);
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(res.getContentType()).startsWith(Xlsx.XLSX.toString());
    assertThat(res.getHeader("Content-Disposition")).contains("dang-ky-" + N2 + ".xlsx");
    return new XSSFWorkbook(new ByteArrayInputStream(res.getContentAsByteArray()));
  }

  private String upload(XSSFWorkbook wb, boolean apply) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    wb.write(out);
    MockHttpServletResponse res =
        mvc.perform(
                multipart(
                        "/api/admin/registrations/units/"
                            + UNIT
                            + "/import?weekStart="
                            + N2
                            + "&apply="
                            + apply)
                    .file(
                        new MockMultipartFile(
                            "file", "dang-ky.xlsx", Xlsx.XLSX.toString(), out.toByteArray()))
                    .cookie(dept))
            .andReturn()
            .getResponse();
    res.setCharacterEncoding("UTF-8");
    assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
    return res.getContentAsString();
  }

  private static String text(Sheet sheet, int row, int column) {
    Row r = sheet.getRow(row - 1);
    return r == null || r.getCell(column - 1) == null
        ? ""
        : new DataFormatter().formatCellValue(r.getCell(column - 1));
  }

  @Test
  @Order(50)
  @DisplayName("downloads the grid as a file, previews the changes and applies them at once")
  void importWeek() throws Exception {
    moveTo(FRI, "00:01");
    String code;
    try (XSSFWorkbook wb = template()) {
      Sheet sheet = wb.getSheetAt(0);
      assertThat(text(sheet, 1, 1)).isEqualTo("Mã NV");
      assertThat(sheet.getRow(1).getZeroHeight()).isTrue();
      code = text(sheet, 3, 1);
      // Unchanged file: nothing to do.
      String unchanged = upload(wb, false);
      assertThat(at(unchanged, "$.status").toString()).isEqualTo("PREVIEW");
      assertThat(JsonPath.<Integer>read(unchanged, "$.changedDays")).isZero();
      // 2 meals on the first column (Monday's first dish) and 1 on the last.
      Row row = sheet.getRow(2);
      row.createCell(3).setCellValue(2);
      row.createCell(sheet.getRow(1).getLastCellNum() - 1).setCellValue(1);
      String preview = upload(wb, false);
      assertThat(at(preview, "$.status").toString()).isEqualTo("PREVIEW");
      assertThat(JsonPath.<Integer>read(preview, "$.changedDays")).isEqualTo(2);
      assertThat(JsonPath.<Integer>read(preview, "$.changedEmployees")).isEqualTo(1);
      String applied = upload(wb, true);
      assertThat(at(applied, "$.status").toString()).isEqualTo("APPLIED");
      assertThat(JsonPath.<Integer>read(applied, "$.changedDays")).isEqualTo(2);
    }
    String grid = read(dept, gridUrl(N2));
    String first = at(grid, "$.days[0].items[0].id");
    assertThat(
            JsonPath.<List<Integer>>read(
                grid,
                "$.rows[?(@.employeeCode == '%s')].cells['%s'].quantities['%s']"
                    .formatted(code, N2, first)))
        .containsExactly(2);
    assertThat(
            count(
                """
                SELECT count(*) FROM "AuditLog"
                WHERE action = 'registration.onBehalf' AND "entityId" LIKE :prefix
                """,
                Map.of("prefix", code + ":%")))
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  @Order(51)
  @DisplayName("one bad cell rejects the whole file and saves nothing")
  void importRejects() throws Exception {
    moveTo(FRI, "00:01");
    String code;
    try (XSSFWorkbook wb = template()) {
      Sheet sheet = wb.getSheetAt(0);
      code = text(sheet, 3, 1);
      sheet.getRow(2).createCell(3).setCellValue(5);
      sheet.getRow(3).createCell(3).setCellValue("1.5");
      sheet.createRow(sheet.getLastRowNum() + 1).createCell(0).setCellValue("PNJP999999");
      String res = upload(wb, true);
      assertThat(at(res, "$.status").toString()).isEqualTo("REJECTED");
      assertThat(JsonPath.<List<String>>read(res, "$.errors[*].code"))
          .containsExactly("QUANTITY_INVALID", "EMPLOYEE_NOT_IN_LIST");
    }
    String grid = read(dept, gridUrl(N2));
    String first = at(grid, "$.days[0].items[0].id");
    assertThat(
            JsonPath.<List<Integer>>read(
                grid,
                "$.rows[?(@.employeeCode == '%s')].cells['%s'].quantities['%s']"
                    .formatted(code, N2, first)))
        .doesNotContain(5);
  }

  @Test
  @Order(52)
  @DisplayName("a file that is not the template is refused")
  void importRefused() throws Exception {
    moveTo(FRI, "00:01");
    MockHttpServletResponse res =
        mvc.perform(
                multipart("/api/admin/registrations/units/" + UNIT + "/import?weekStart=" + N2)
                    .file(
                        new MockMultipartFile(
                            "file", "x.xlsx", Xlsx.XLSX.toString(), "not excel".getBytes()))
                    .cookie(dept))
            .andReturn()
            .getResponse();
    assertThat(res.getStatus()).isEqualTo(400);
  }

  // --- plan 7.1 rule 5: a 403 for every admin endpoint out of scope ------------------------------

  private List<Endpoint> unitEndpoints() {
    return List.of(
        Endpoint.get(gridUrl(N2)),
        Endpoint.of(
            HttpMethod.PUT,
            "/api/admin/registrations/units/" + UNIT + "/days/" + plus(N2, 4),
            "{'canteenId':'ct_gv','rows':[{'employeeCode':'" + Demo.EMPLOYEE + "','lines':[]}]}"),
        Endpoint.of(
            HttpMethod.POST,
            "/api/admin/registrations/employees/" + Demo.EMPLOYEE + "/extras",
            "{'date':'" + FRI + "','menuItemId':'x','quantity':1,'reason':'x'}"),
        Endpoint.get("/api/admin/registrations/units/" + UNIT + "/import-template?weekStart=" + N2),
        Endpoint.of(
            HttpMethod.POST,
            "/api/admin/registrations/units/" + UNIT + "/import?weekStart=" + N2,
            null));
  }

  @Test
  @Order(60)
  @DisplayName("a dept admin of another workshop cannot open, save or add extras for these units")
  void forbiddenOtherWorkshop() throws Exception {
    expectForbidden(List.of(deptOther), unitEndpoints());
  }

  @Test
  @Order(61)
  @DisplayName("employees and the system admin cannot use any registration admin endpoint")
  void forbiddenEmployees() throws Exception {
    List<Endpoint> all =
        new ArrayList<>(
            List.of(
                Endpoint.get("/api/admin/registrations/units"),
                Endpoint.get("/api/admin/registrations/org-units"),
                Endpoint.get("/api/admin/registrations/progress?weekStart=" + N2),
                Endpoint.get("/api/admin/registrations/not-registered?weekStart=" + N2)));
    all.addAll(unitEndpoints());
    expectForbidden(List.of(a, sys), all);
  }

  @Test
  @Order(62)
  @DisplayName("BR-27: an employee cannot view another unit's colleagues; no meal unit, no team")
  void forbiddenTeam() throws Exception {
    expectForbidden(List.of(a), List.of(Endpoint.get("/api/team/units/ou_XKH-DUC")));
    expectForbidden(List.of(unassigned), List.of(Endpoint.get("/api/team")));
    read(a, "/api/team/units/ou_XCT-A1");
  }
}
