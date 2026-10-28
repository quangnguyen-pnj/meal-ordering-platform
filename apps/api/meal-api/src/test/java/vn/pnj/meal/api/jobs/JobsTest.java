package vn.pnj.meal.api.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
import vn.pnj.meal.api.access.SystemAdminBootstrap;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.dmp.DmpClient;
import vn.pnj.meal.api.dmp.MockDmpClient;
import vn.pnj.meal.api.org.MealUnits;
import vn.pnj.meal.api.org.OrgSyncService;
import vn.pnj.meal.api.registrations.ForecastService;
import vn.pnj.meal.api.seed.SeedEmployees.Demo;
import vn.pnj.meal.api.settings.SettingsService;
import vn.pnj.meal.api.support.IntegrationTest;

/**
 * The jobs that act on registrations: close-week (BR-07), reselect-deadline (BR-14) and the leaver
 * cancellation of the org sync (BR-36). The legacy suite tests them through the registration API
 * (J3); here the registrations are written directly.
 *
 * <p>Week N starts Monday 2028-05-01; its regular closeAt is 00:00 Friday 2028-04-28.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JobsTest extends IntegrationTest {
  private static final String WEEK = "2028-05-01";
  private static final String B = "PNJP000060";
  private static final String C = "PNJP000061";

  @Autowired Jobs jobs;
  @Autowired JsonMapper jsonMapper;
  @Autowired TransactionTemplate tx;
  @Autowired AuditService audit;
  @Autowired SystemAdminBootstrap bootstrap;
  @Autowired SettingsService settings;
  @Autowired MealUnits mealUnits;
  @Autowired ForecastService forecast;

  private String[] vnNow = {"2028-04-24", "10:00"}; // Monday of week N-1
  private Cookie hc;
  private String weekId;
  private final List<String> items = new ArrayList<>();

  @BeforeEach
  void atNow() throws Exception {
    setVnTime(vnNow[0], vnNow[1]);
    hc = signInReady(Demo.HC_ADMIN);
  }

  private void moveTo(String date, String time) throws Exception {
    vnNow = new String[] {date, time};
    atNow();
  }

  private String register(String employeeCode, String date, int quantity, String status) {
    String id = Ids.newId(clock.now().toEpochMilli());
    jdbc.sql(
            """
            INSERT INTO "Registration" (id, "employeeId", "menuItemId", date, quantity, source,
                status, "updatedAt")
            VALUES (:id, :e, :item, :date, :q, 'SELF', CAST(:status AS "RegistrationStatus"), now())
            """)
        .param("id", id)
        .param("e", employeeId(employeeCode))
        .param("item", items.get(LocalDate.parse(date).getDayOfWeek().getValue() - 1))
        .param("date", LocalDate.parse(date))
        .param("q", quantity)
        .param("status", status)
        .update();
    return id;
  }

  private String status(String registrationId) {
    return jdbc.sql("SELECT status::text FROM \"Registration\" WHERE id = :id")
        .param("id", registrationId)
        .query(String.class)
        .single();
  }

  private String regA;
  private String regB;
  private String reselect;

  @Test
  @Order(1)
  @DisplayName("publishes week N with one dish and one dessert a day, and some registrations")
  void setUp() throws Exception {
    String dish =
        JsonPath.read(
            expect(
                201,
                hc,
                HttpMethod.POST,
                "/api/admin/dishes",
                "{'canteenId':'ct_gv','name':'Cơm gà job','typeId':'dt_fac_gv_MAN','sessions':['LUNCH']}"),
            "$.id");
    String dessert =
        JsonPath.read(
            expect(
                201,
                hc,
                HttpMethod.POST,
                "/api/admin/desserts",
                "{'canteenId':'ct_gv','name':'Chè job'}"),
            "$.id");
    List<String> days = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      days.add(
          "{'date':'%s','isServing':true,'items':[{'dishId':'%s'}],'dessertIds':['%s']}"
              .formatted(LocalDate.parse(WEEK).plusDays(i), dish, dessert));
    }
    String week =
        expect(
            200,
            hc,
            HttpMethod.POST,
            "/api/admin/menu/weeks/publish",
            "{'canteenId':'ct_gv','weekStart':'%s','days':[%s]}"
                .formatted(WEEK, String.join(",", days)));
    weekId = JsonPath.read(week, "$.id");
    assertThat(JsonPath.<String>read(week, "$.closeAt")).isEqualTo("2028-04-27T17:00:00.000Z");
    items.addAll(JsonPath.<List<String>>read(week, "$.days[*].items[0].id"));

    regA = register(Demo.EMPLOYEE, "2028-05-01", 1, "ACTIVE");
    register(Demo.EMPLOYEE, "2028-05-02", 2, "ACTIVE");
    regB = register(B, "2028-05-01", 0, "ACTIVE"); // no meal: not in the forecast
    reselect = register(B, "2028-05-03", 1, "NEEDS_RESELECT"); // BR-14: not in the forecast
    register(B, "2028-05-04", 1, "CANCELLED");
  }

  @Test
  @Order(2)
  @DisplayName("BR-07: before closeAt the close-week job leaves the week open")
  void notYet() throws Exception {
    moveTo("2028-04-27", "23:59");
    jobs.closeWeek();
    assertThat(
            jdbc.sql("SELECT status::text FROM \"MenuWeek\" WHERE id = :id")
                .param("id", weekId)
                .query(String.class)
                .single())
        .isEqualTo("PUBLISHED");
  }

  @Test
  @Order(3)
  @DisplayName("BR-07: at Friday 00:00 the close-week job takes the forecast snapshot, once")
  void closeWeek() throws Exception {
    moveTo("2028-04-28", "00:00");
    assertThat(jobs.closeWeek()).isGreaterThanOrEqualTo(1);
    assertThat(jobs.closeWeek()).isZero();
    assertThat(
            jdbc.sql("SELECT status::text FROM \"MenuWeek\" WHERE id = :id")
                .param("id", weekId)
                .query(String.class)
                .single())
        .isEqualTo("CLOSED");
    List<Map<String, Object>> lines =
        jdbc.sql(
                """
                SELECT e."employeeCode", l.date::text, l.quantity, l."mealUnitId", l."floorId",
                       l."canteenId"
                FROM "ForecastLine" l JOIN "Employee" e ON e.id = l."employeeId"
                WHERE l."menuWeekId" = :id ORDER BY l.date
                """)
            .param("id", weekId)
            .query()
            .listOfRows();
    // Only active lines with a positive quantity; BR-64: with the mapping in effect at closeAt.
    assertThat(lines)
        .containsExactly(
            Map.of(
                "employeeCode", Demo.EMPLOYEE,
                "date", "2028-05-01",
                "quantity", 1,
                "mealUnitId", "ou_XCT-A1",
                "floorId", "fl_T2",
                "canteenId", "ct_gv"),
            Map.of(
                "employeeCode", Demo.EMPLOYEE,
                "date", "2028-05-02",
                "quantity", 2,
                "mealUnitId", "ou_XCT-A1",
                "floorId", "fl_T2",
                "canteenId", "ct_gv"));
    Map<String, Object> snapshot =
        jdbc.sql(
                """
                SELECT "actorId", after::text FROM "AuditLog"
                WHERE action = 'forecast.snapshot' AND "entityId" = :id
                """)
            .param("id", weekId)
            .query()
            .singleRow();
    assertThat(snapshot.get("actorId")).isNull();
    assertThat(JsonPath.<Integer>read((String) snapshot.get("after"), "$.meals")).isEqualTo(3);
    assertThat(JsonPath.<String>read((String) snapshot.get("after"), "$.closeAt"))
        .isEqualTo("2028-04-27T17:00:00.000Z");
    assertThat(status(regA)).isEqualTo("ACTIVE");
    assertThat(status(regB)).isEqualTo("ACTIVE");
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
  @Order(4)
  @DisplayName(
      "BR-36: a leaver loses their meals from the end date on sync; a locked day is left alone")
  void leaver() throws Exception {
    moveTo("2028-05-02", "09:00"); // Tuesday after its 08:00 lock
    String tue = register(C, "2028-05-02", 1, "ACTIVE");
    String wed = register(C, "2028-05-03", 1, "ACTIVE");
    String thu = register(C, "2028-05-04", 1, "NEEDS_RESELECT");
    String fri = register(C, "2028-05-05", 1, "ACTIVE");
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
            forecast,
            leaving(C, LocalDate.of(2028, 5, 2)));
    try {
      OrgSyncService.Result result = sync.run("fac_gv", null);
      assertThat(result.status()).isEqualTo("SUCCESS");
      assertThat(result.details().mealsCancelledForLeavers()).isEqualTo(3);
      assertThat(result.details().employees().left()).isEqualTo(1);
      // Tuesday is the end date but its lock has passed: reconciliation shows it instead.
      assertThat(List.of(status(tue), status(wed), status(thu), status(fri)))
          .containsExactly("ACTIVE", "CANCELLED", "CANCELLED", "CANCELLED");
      assertThat(
              jdbc.sql(
                      """
                      SELECT count(*) FROM "AuditLog"
                      WHERE action = 'registration.cancelledLeaver' AND "actorId" IS NULL
                        AND "entityId" IN (:ids)
                      """)
                  .param("ids", List.of(wed, thu, fri))
                  .query(Integer.class)
                  .single())
          .isEqualTo(3);
    } finally {
      // The plain mock brings the employee back for the other test classes.
      jobs.syncOrgEmployees();
    }
    assertThat(
            jdbc.sql("SELECT status::text FROM \"Employee\" WHERE \"employeeCode\" = :c")
                .param("c", C)
                .query(String.class)
                .single())
        .isEqualTo("ACTIVE");
  }

  @Test
  @Order(5)
  @DisplayName("BR-14: a meal waiting for reselection is cancelled at its day's lock, once")
  void reselectDeadline() throws Exception {
    moveTo("2028-05-03", "07:59");
    jobs.reselectDeadline();
    assertThat(status(reselect)).isEqualTo("NEEDS_RESELECT");
    moveTo("2028-05-03", "08:00");
    assertThat(jobs.reselectDeadline()).isGreaterThanOrEqualTo(1);
    assertThat(jobs.reselectDeadline()).isZero();
    assertThat(status(reselect)).isEqualTo("CANCELLED");
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM "AuditLog"
                    WHERE action = 'registration.cancelledUnselected' AND "actorId" IS NULL
                      AND "entityId" = :id
                    """)
                .param("id", reselect)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }
}
