package vn.pnj.meal.api.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.dmp.MockDmpClient;
import vn.pnj.meal.api.seed.SeedEmployees.Demo;
import vn.pnj.meal.api.settings.SettingsService;
import vn.pnj.meal.api.support.IntegrationTest;

/**
 * P3 exit criteria (plan 7, P3 "Done when") with the mock DMP scenarios and fake time: attendance
 * polling and the 08:10 cutoff (BR-30, BR-31), automatic restore (BR-32), presence confirmation
 * (BR-33), the safety check (BR-34), leave flags (BR-35), admin edits after the lock (BR-24) and
 * /admin/today with its cases (BR-41).
 *
 * <p>Two published weeks, 2029-03-05 and 2029-03-12, one scenario per serving day. Employees with
 * meals every day: PNJP000001 and PNJP000008..PNJP000040 (the scenarios act on 008..020).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AttendanceTest extends IntegrationTest {
  private static final List<String> WEEKS = List.of("2029-03-05", "2029-03-12");
  private static final String AFFECTED = "PNJP000010";
  private static final String ON_TIME = "PNJP000030";

  @Autowired JsonMapper jsonMapper;
  @Autowired TransactionTemplate tx;
  @Autowired AuditService audit;
  @Autowired SettingsService settings;

  private final Map<LocalDate, String> items = new HashMap<>();
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
        new MockDmpClient(scenario, jsonMapper, clock::now));
  }

  private static String code(int n) {
    return "PNJP%06d".formatted(n);
  }

  private void register(String employeeCode, LocalDate date, int quantity) {
    jdbc.sql(
            """
            INSERT INTO "Registration" (id, "employeeId", "menuItemId", date, quantity, source,
                status, "updatedAt")
            VALUES (:id, :e, :item, :date, :q, 'SELF', 'ACTIVE', now())
            """)
        .param("id", Ids.newId(clock.now().toEpochMilli()))
        .param("e", employeeId(employeeCode))
        .param("item", items.get(date))
        .param("date", date)
        .param("q", quantity)
        .update();
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

  private long countState(String date, String state) {
    return jdbc.sql(
            """
            SELECT count(*) FROM "DailyPresence"
            WHERE date = CAST(:d AS date) AND state = CAST(:s AS "PresenceState")
            """)
        .param("d", date)
        .param("s", state)
        .query(Long.class)
        .single();
  }

  private long audits(String action, String date) {
    return jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE action = :a AND \"entityId\" LIKE :d")
        .param("a", action)
        .param("d", "%:" + date)
        .query(Long.class)
        .single();
  }

  /**
   * The database is shared by every test class, and other classes list the future meals of the same
   * employees (e.g. the BR-36 leaver check of {@code RegistrationTest}), so the test's own meals go
   * away at the end. Only this test's rows: nothing else is dated in these weeks.
   */
  @AfterAll
  void removeMeals() {
    jdbc.sql(
            """
            DELETE FROM "Registration" WHERE date BETWEEN '2029-03-05' AND '2029-03-16'
            """)
        .update();
  }

  /** Runs the poll-attendance / provisional-cancel job at a Vietnam time. */
  private void tickAt(AttendanceService service, String date, String time) {
    setVnTime(date, time);
    service.tick(factoryId);
  }

  @Test
  @Order(1)
  @DisplayName("publishes two weeks with one dish a day and registers meals")
  void setUp() throws Exception {
    setVnTime("2029-02-26", "10:00");
    factoryId = jdbc.sql("SELECT id FROM \"Factory\" LIMIT 1").query(String.class).single();
    Cookie hc = signInReady(Demo.HC_ADMIN);
    String dish =
        JsonPath.read(
            expect(
                201,
                hc,
                HttpMethod.POST,
                "/api/admin/dishes",
                "{'canteenId':'ct_gv','name':'Cơm gà P3','typeId':'dt_fac_gv_MAN','sessions':['LUNCH']}"),
            "$.id");
    String dessert =
        JsonPath.read(
            expect(
                201,
                hc,
                HttpMethod.POST,
                "/api/admin/desserts",
                "{'canteenId':'ct_gv','name':'Chè P3'}"),
            "$.id");
    for (String weekStart : WEEKS) {
      List<String> days = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        days.add(
            "{'date':'%s','isServing':true,'items':[{'dishId':'%s'}],'dessertIds':['%s']}"
                .formatted(LocalDate.parse(weekStart).plusDays(i), dish, dessert));
      }
      String week =
          expect(
              200,
              hc,
              HttpMethod.POST,
              "/api/admin/menu/weeks/publish",
              "{'canteenId':'ct_gv','weekStart':'%s','days':[%s]}"
                  .formatted(weekStart, String.join(",", days)));
      List<String> ids = JsonPath.read(week, "$.days[*].items[0].id");
      for (int i = 0; i < 5; i++) {
        items.put(LocalDate.parse(weekStart).plusDays(i), ids.get(i));
      }
    }
    withMeals.add(Demo.EMPLOYEE);
    for (int n = 8; n <= 40; n++) {
      withMeals.add(code(n));
    }
    for (LocalDate date : items.keySet()) {
      for (String c : withMeals) {
        register(c, date, 1);
      }
    }
  }

  @Test
  @Order(2)
  @DisplayName(
      "BR-30 / BR-31: polling from 07:00; at 08:10 no check-in in time = provisionally cancelled")
  void lateScenario() {
    AttendanceService late = service("late");
    String day = "2029-03-05";
    tickAt(late, day, "06:59");
    assertThat(state(ON_TIME, day)).isEqualTo("NONE");
    tickAt(late, day, "07:00");
    assertThat(state(ON_TIME, day)).isEqualTo("PRESENT");
    assertThat(state(AFFECTED, day)).isEqualTo("UNKNOWN"); // 08:15 check-in: not in time
    tickAt(late, day, "08:10");
    assertThat(state(ON_TIME, day)).isEqualTo("PRESENT");
    assertThat(state(Demo.EMPLOYEE, day)).isEqualTo("PRESENT");
    assertThat(state(AFFECTED, day)).isEqualTo("PROVISIONAL_ABSENT");
    assertThat(countState(day, "PROVISIONAL_ABSENT")).isEqualTo(13);
    assertThat(audits("presence.provisionallyCancelled", day)).isEqualTo(13);
    Map<String, Object> cutoff =
        jdbc.sql(
                """
                SELECT outcome::text, "withMeals", "presentWithMeals", "cancelledCount"
                FROM "AttendanceDay" WHERE date = '2029-03-05'
                """)
            .query()
            .singleRow();
    assertThat(cutoff)
        .containsEntry("outcome", "CANCELLED")
        .containsEntry("withMeals", 34)
        .containsEntry("presentWithMeals", 21)
        .containsEntry("cancelledCount", 13);
  }

  @Test
  @Order(3)
  @DisplayName("BR-32: a 08:15 check-in does not restore; the cutoff runs only once")
  void lateDoesNotRestore() {
    AttendanceService late = service("late");
    String day = "2029-03-05";
    tickAt(late, day, "08:20");
    assertThat(state(AFFECTED, day)).isEqualTo("PROVISIONAL_ABSENT");
    assertThat(audits("presence.restored", day)).isZero();
    assertThat(audits("presence.provisionallyCancelled", day)).isEqualTo(13);
  }

  @Test
  @Order(4)
  @DisplayName("BR-32: a 08:05 check-in arriving at 08:20 restores automatically")
  void lateRecordRestores() {
    AttendanceService lateRecord = service("late-record");
    String day = "2029-03-06";
    tickAt(lateRecord, day, "07:30");
    tickAt(lateRecord, day, "08:10");
    assertThat(state(AFFECTED, day)).isEqualTo("PROVISIONAL_ABSENT");
    tickAt(lateRecord, day, "08:15");
    assertThat(state(AFFECTED, day)).isEqualTo("PROVISIONAL_ABSENT");
    tickAt(lateRecord, day, "08:20");
    assertThat(state(AFFECTED, day)).isEqualTo("PRESENT");
    assertThat(countState(day, "PROVISIONAL_ABSENT")).isZero();
    assertThat(audits("presence.restored", day)).isEqualTo(13);
  }

  @Test
  @Order(5)
  @DisplayName(
      "BR-30 / BR-32 (v1.3): a manager's DMP confirmation restores, whatever the arrival time")
  void managerConfirmed() throws Exception {
    AttendanceService confirmed = service("manager-confirmed");
    String day = "2029-03-07";
    tickAt(confirmed, day, "07:30");
    tickAt(confirmed, day, "08:10");
    assertThat(countState(day, "PROVISIONAL_ABSENT")).isEqualTo(13);
    tickAt(confirmed, day, "08:20");
    // 008..014 forgot to check in; the confirmation reached DMP at 08:20.
    assertThat(state(AFFECTED, day)).isEqualTo("PRESENT");
    assertThat(state(code(15), day)).isEqualTo("PROVISIONAL_ABSENT");
    tickAt(confirmed, day, "08:25");
    // 015..020 checked in at 08:25, after the cutoff, and were confirmed: restored too.
    assertThat(state(code(15), day)).isEqualTo("PRESENT");
    assertThat(countState(day, "PROVISIONAL_ABSENT")).isZero();

    Cookie hc = signInReady(Demo.HC_ADMIN);
    String view = read(hc, "/api/admin/today?date=" + day);
    assertThat(
            JsonPath.<List<String>>read(view, "$.rows[?(@.employeeCode == 'PNJP000015')].cases[*]"))
        .contains("PRESENCE_CONFIRMED_IN_DMP");
    assertThat(
            JsonPath.<List<Boolean>>read(
                view, "$.rows[?(@.employeeCode == 'PNJP000015')].presence.confirmedInDmp"))
        .containsExactly(true);
    assertThat(
            JsonPath.<List<String>>read(view, "$.rows[?(@.employeeCode == 'PNJP000030')].cases[*]"))
        .isEmpty();
  }

  @Test
  @Order(6)
  @DisplayName("BR-34: a failed sync keeps every meal and warns the HC admin; the re-run cancels")
  void apiError() throws Exception {
    AttendanceService broken = service("api-error");
    String day = "2029-03-08";
    tickAt(broken, day, "07:30");
    tickAt(broken, day, "08:10");
    assertThat(countState(day, "PROVISIONAL_ABSENT")).isZero();
    assertThat(
            jdbc.sql("SELECT outcome::text FROM \"AttendanceDay\" WHERE date = '2029-03-08'")
                .query(String.class)
                .single())
        .isEqualTo("HELD_SYNC_FAILED");
    // A later automatic poll never cancels after a hold (BR-34).
    tickAt(broken, day, "08:15");
    assertThat(countState(day, "PROVISIONAL_ABSENT")).isZero();

    Cookie hc = signInReady(Demo.HC_ADMIN);
    String banners = read(hc, "/api/me/banners");
    assertThat(JsonPath.<List<String>>read(banners, "$.items[*].kind"))
        .contains("ATTENDANCE_SYNC_FAILED", "ATTENDANCE_CUTOFF_HELD");
    Cookie employee = signInReady(Demo.EMPLOYEE);
    assertThat(JsonPath.<List<String>>read(read(employee, "/api/me/banners"), "$.items[*].kind"))
        .doesNotContain("ATTENDANCE_SYNC_FAILED", "ATTENDANCE_CUTOFF_HELD");

    // The HC admin re-runs; the API's mock DMP is "on-time", so everyone is present.
    setVnTime(day, "08:20");
    hc = signInReady(Demo.HC_ADMIN);
    String rerun =
        expect(200, hc, HttpMethod.POST, "/api/admin/attendance/sync", "{'date':'" + day + "'}");
    assertThat(JsonPath.<String>read(rerun, "$.attendance.status")).isEqualTo("SUCCESS");
    assertThat(JsonPath.<String>read(rerun, "$.cutoff.outcome")).isEqualTo("CANCELLED");
    assertThat(JsonPath.<Integer>read(rerun, "$.cutoff.cancelledCount")).isZero();
    assertThat(JsonPath.<List<String>>read(read(hc, "/api/me/banners"), "$.items[*].kind"))
        .doesNotContain("ATTENDANCE_SYNC_FAILED", "ATTENDANCE_CUTOFF_HELD");
  }

  @Test
  @Order(7)
  @DisplayName("BR-34: under 50% of employees with meals present keeps every meal")
  void tooLittleData() {
    String day = "2029-03-09";
    setVnTime("2029-03-09", "06:00");
    // The scenario has attendance for the first 30% of employees only (codes up to 510).
    for (int n = 1001; n <= 1040; n++) {
      register(code(n), LocalDate.parse(day), 1);
    }
    AttendanceService little = service("too-little-data");
    tickAt(little, day, "07:30");
    tickAt(little, day, "08:10");
    assertThat(countState(day, "PROVISIONAL_ABSENT")).isZero();
    assertThat(
            jdbc.sql(
                    """
                    SELECT outcome::text || ' ' || "presentWithMeals" || '/' || "withMeals"
                    FROM "AttendanceDay" WHERE date = '2029-03-09'
                    """)
                .query(String.class)
                .single())
        .isEqualTo("HELD_TOO_FEW_PRESENT 34/74");
  }

  @Test
  @Order(8)
  @DisplayName("BR-35: morning leave loses lunch through attendance; the leave flag never cancels")
  void morningLeave() throws Exception {
    AttendanceService am = service("morning-leave");
    String day = "2029-03-12";
    setVnTime(day, "06:30");
    AttendanceService.LeaveResult leave = am.syncLeave(factoryId, null);
    assertThat(leave.status()).isEqualTo("SUCCESS");
    assertThat(leave.flags()).isEqualTo(13 * 15);
    tickAt(am, day, "07:30");
    assertThat(state(AFFECTED, day)).isEqualTo("UNKNOWN");
    tickAt(am, day, "08:10");
    assertThat(state(AFFECTED, day)).isEqualTo("PROVISIONAL_ABSENT");
    tickAt(am, day, "13:05"); // arrives at 13:00: still cancelled (BR-32)
    assertThat(state(AFFECTED, day)).isEqualTo("PROVISIONAL_ABSENT");

    Cookie hc = signInReady(Demo.HC_ADMIN);
    String view = read(hc, "/api/admin/today?date=" + day);
    assertThat(
            JsonPath.<List<String>>read(
                view, "$.rows[?(@.employeeCode == 'PNJP000010')].leave.dayType"))
        .containsExactly("AM");
    assertThat(
            JsonPath.<List<String>>read(view, "$.rows[?(@.employeeCode == 'PNJP000010')].cases[*]"))
        .contains("PROVISIONALLY_CANCELLED", "ON_LEAVE_WITH_MEALS");
    assertThat(
            JsonPath.<List<Boolean>>read(
                view, "$.rows[?(@.employeeCode == 'PNJP000010')].mealsCount"))
        .containsExactly(false);
    assertThat(JsonPath.<Integer>read(view, "$.summary.provisionallyCancelled")).isEqualTo(13);
    assertThat(JsonPath.<Integer>read(view, "$.summary.countedMeals")).isEqualTo(21);
    // Registrations are never touched (plan 6.3, rule 7).
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM "Registration"
                    WHERE date = '2029-03-12' AND status = 'ACTIVE' AND quantity = 1
                    """)
                .query(Long.class)
                .single())
        .isEqualTo(34);
  }

  @Test
  @Order(9)
  @DisplayName("BR-35: afternoon leave keeps lunch; flags DMP no longer returns are removed")
  void afternoonLeave() {
    AttendanceService pm = service("afternoon-leave");
    String day = "2029-03-13";
    setVnTime(day, "06:30");
    pm.syncLeave(factoryId, null);
    assertThat(
            jdbc.sql(
                    """
                    SELECT "dayType"::text FROM "LeaveFlag"
                    WHERE "employeeId" = :e AND date = '2029-03-13' AND "removedAt" IS NULL
                    """)
                .param("e", employeeId(AFFECTED))
                .query(String.class)
                .single())
        .isEqualTo("PM");
    tickAt(pm, day, "07:30");
    tickAt(pm, day, "08:10");
    assertThat(state(AFFECTED, day)).isEqualTo("PRESENT");

    service("on-time").syncLeave(factoryId, null);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM "LeaveFlag"
                    WHERE date = '2029-03-13' AND "removedAt" IS NULL
                    """)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  @Order(10)
  @DisplayName(
      "BR-33: dept and HC admins confirm presence until finalization; the reason is optional")
  void confirmPresence() throws Exception {
    AttendanceService forgot = service("forgot-to-check-in");
    String day = "2029-03-14";
    tickAt(forgot, day, "07:30");
    tickAt(forgot, day, "08:10");
    assertThat(state(AFFECTED, day)).isEqualTo("PROVISIONAL_ABSENT");

    setVnTime(day, "08:20");
    Cookie hc = signInReady(Demo.HC_ADMIN);
    String confirmed =
        expect(
            200,
            hc,
            HttpMethod.POST,
            "/api/admin/today/employees/" + AFFECTED + "/presence",
            "{'date':'" + day + "'}");
    assertThat(JsonPath.<String>read(confirmed, "$.state")).isEqualTo("PRESENT");
    assertThat(state(AFFECTED, day)).isEqualTo("PRESENT");
    Map<String, Object> entry =
        jdbc.sql(
                """
                SELECT "actorId", reason, before::text AS before FROM "AuditLog"
                WHERE action = 'presence.confirmed' AND "entityId" = :id
                """)
            .param("id", AFFECTED + ":" + day)
            .query()
            .singleRow();
    assertThat(entry.get("actorId")).isEqualTo(employeeId(Demo.HC_ADMIN));
    assertThat(entry.get("reason")).isNull();
    assertThat((String) entry.get("before")).contains("PROVISIONAL_ABSENT");
    // Confirming twice changes nothing.
    expect(
        200,
        hc,
        HttpMethod.POST,
        "/api/admin/today/employees/" + AFFECTED + "/presence",
        "{'date':'" + day + "','reason':'again'}");
    assertThat(audits("presence.confirmed", day)).isEqualTo(1);

    // A dept admin confirms an employee of their units, with a reason.
    Cookie production = signInReady(Demo.DEPT_ADMIN_PRODUCTION);
    String withReason =
        expect(
            200,
            production,
            HttpMethod.POST,
            "/api/admin/today/employees/" + Demo.EMPLOYEE + "/presence",
            "{'date':'" + day + "','reason':'  Quên chấm công  '}");
    assertThat(JsonPath.<String>read(withReason, "$.confirmReason")).isEqualTo("Quên chấm công");
    String view = read(production, "/api/admin/today?date=" + day);
    assertThat(
            JsonPath.<List<String>>read(view, "$.rows[?(@.employeeCode == 'PNJP000001')].cases[*]"))
        .contains("PRESENCE_CONFIRMED_BY_ADMIN");

    // The employee sees it on /today, without the reason or the admin's name.
    Cookie employee = signInReady(Demo.EMPLOYEE);
    String today = read(employee, "/api/registrations/me/day?date=" + day);
    assertThat(JsonPath.<Boolean>read(today, "$.attendance.confirmedByAdmin")).isTrue();
    assertThat(JsonPath.<String>read(today, "$.attendance.state")).isEqualTo("PRESENT");
    assertThat(today).doesNotContain("Quên chấm công").doesNotContain("confirmReason");

    // The next day the window is closed (TODO(BR-43): finalization in P4).
    setVnTime("2029-03-15", "08:00");
    hc = signInReady(Demo.HC_ADMIN);
    expect(
        409,
        hc,
        HttpMethod.POST,
        "/api/admin/today/employees/" + code(11) + "/presence",
        "{'date':'" + day + "'}");
  }

  @Test
  @Order(11)
  @DisplayName("BR-24 / BR-25: after 08:00 an admin edits meals with a reason; additions are extra")
  void editAfterLock() throws Exception {
    String day = "2029-03-15";
    String item = items.get(LocalDate.parse(day));
    setVnTime(day, "07:59");
    Cookie hc = signInReady(Demo.HC_ADMIN);
    String url = "/api/admin/today/employees/" + Demo.EMPLOYEE + "/meals";
    String body = "{'date':'" + day + "','lines':[{'menuItemId':'" + item + "','quantity':2}]";
    expect(409, hc, HttpMethod.PUT, url, body + ",'reason':'Khách'}");
    setVnTime(day, "08:30");
    hc = signInReady(Demo.HC_ADMIN);
    expect(400, hc, HttpMethod.PUT, url, body + "}");
    expect(400, hc, HttpMethod.PUT, url, body + ",'reason':''}");
    String edited = expect(200, hc, HttpMethod.PUT, url, body + ",'reason':'Khách thăm xưởng'}");
    assertThat(JsonPath.<Integer>read(edited, "$.after." + item)).isEqualTo(2);
    Map<String, Object> reg =
        jdbc.sql(
                """
                SELECT quantity, "isExtra", "extraReason" FROM "Registration"
                WHERE "employeeId" = :e AND date = '2029-03-15'
                """)
            .param("e", employeeId(Demo.EMPLOYEE))
            .query()
            .singleRow();
    assertThat(reg)
        .containsEntry("quantity", 2)
        .containsEntry("isExtra", true)
        .containsEntry("extraReason", "Khách thăm xưởng");
    assertThat(
            jdbc.sql(
                    """
                    SELECT reason FROM "AuditLog"
                    WHERE action = 'registration.adminEdit' AND "entityId" = :id
                    """)
                .param("id", Demo.EMPLOYEE + ":" + day)
                .query(String.class)
                .single())
        .isEqualTo("Khách thăm xưởng");
    // Not eating: down to zero.
    expect(200, hc, HttpMethod.PUT, url, "{'date':'" + day + "','lines':[],'reason':'Về sớm'}");
    // A dish that is not on that day's menu is refused.
    expect(
        400,
        hc,
        HttpMethod.PUT,
        url,
        "{'date':'" + day + "','lines':[{'menuItemId':'nope','quantity':1}],'reason':'x'}");
    setVnTime("2029-03-16", "08:30");
    hc = signInReady(Demo.HC_ADMIN);
    expect(409, hc, HttpMethod.PUT, url, body + ",'reason':'Trễ'}");
  }

  @Test
  @Order(12)
  @DisplayName("/admin/today: dept admins see only their units")
  void scope() throws Exception {
    String day = "2029-03-14";
    setVnTime(day, "09:00");
    Cookie production = signInReady(Demo.DEPT_ADMIN_PRODUCTION);
    Cookie jewelry = signInReady(Demo.DEPT_ADMIN_JEWELRY);
    Cookie hc = signInReady(Demo.HC_ADMIN);
    List<String> all =
        JsonPath.read(read(hc, "/api/admin/today?date=" + day), "$.rows[*].employeeCode");
    List<String> mine =
        JsonPath.read(read(production, "/api/admin/today?date=" + day), "$.rows[*].employeeCode");
    List<String> theirs =
        JsonPath.read(read(jewelry, "/api/admin/today?date=" + day), "$.rows[*].employeeCode");
    assertThat(all).containsAll(withMeals);
    assertThat(mine).contains(Demo.EMPLOYEE).isSubsetOf(all);
    assertThat(theirs).doesNotContain(Demo.EMPLOYEE).isSubsetOf(all);
    assertThat(mine.size() + theirs.size()).isLessThanOrEqualTo(all.size());
  }

  @Test
  @Order(13)
  @DisplayName("plan 7.1 rule 5: out-of-scope and unprivileged callers get 403")
  void forbidden() throws Exception {
    String day = "2029-03-16";
    setVnTime(day, "08:20");
    Cookie employee = signInReady(Demo.EMPLOYEE);
    Cookie jewelry = signInReady(Demo.DEPT_ADMIN_JEWELRY);
    Cookie production = signInReady(Demo.DEPT_ADMIN_PRODUCTION);
    Cookie sys = signInReady(Demo.SYS_ADMIN);
    String presence = "/api/admin/today/employees/" + Demo.EMPLOYEE + "/presence";
    String meals = "/api/admin/today/employees/" + Demo.EMPLOYEE + "/meals";
    String confirmBody = "{'date':'" + day + "'}";
    String mealsBody = "{'date':'" + day + "','lines':[],'reason':'x'}";
    expectForbidden(
        List.of(employee, sys),
        List.of(
            Endpoint.get("/api/admin/today"),
            Endpoint.of(HttpMethod.POST, presence, confirmBody),
            Endpoint.of(HttpMethod.PUT, meals, mealsBody)));
    // A dept admin outside the employee's unit.
    expectForbidden(
        List.of(jewelry),
        List.of(
            Endpoint.of(HttpMethod.POST, presence, confirmBody),
            Endpoint.of(HttpMethod.PUT, meals, mealsBody)));
    // Re-running the sync is for the HC and system admins.
    expectForbidden(
        List.of(employee, production, jewelry),
        List.of(Endpoint.of(HttpMethod.POST, "/api/admin/attendance/sync", "{}")));
  }
}
