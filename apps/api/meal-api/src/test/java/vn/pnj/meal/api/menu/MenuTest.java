package vn.pnj.meal.api.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import vn.pnj.meal.api.common.Xlsx;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.seed.SeedEmployees.Demo;
import vn.pnj.meal.api.support.IntegrationTest;
import vn.pnj.meal.api.support.TestXlsx;

/**
 * Port of apps/api-legacy/test/menu.e2e-spec.ts. P1 exit criteria: the HC admin can build and
 * publish a week; employees see their canteen's published menu and never a draft. Plus BR-08, BR-10
 * to BR-15 and the 403s of every endpoint.
 *
 * <p>Week N starts Monday 2027-03-01; week N-1 is 2027-02-22..28, so the regular closeAt is 00:00
 * Friday 2027-02-26 Vietnam time (2027-02-25T17:00Z).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MenuTest extends IntegrationTest {
  private static final String WEEK = "2027-03-01";
  private static final String CANTEEN = "ct_gv";
  private static final Map<String, String> TYPE =
      Map.of("MAN", "dt_fac_gv_MAN", "CHAY", "dt_fac_gv_CHAY", "CHAO", "dt_fac_gv_CHAO");
  private static final String COLLEAGUE = "PNJP000060";
  private static final List<String> STANDARD = List.of("chay", "chao", "ca", "thit");

  private final Map<String, String> dish = new HashMap<>();
  private final Map<String, String> dessert = new HashMap<>();
  private String weekId;
  private String otherCanteen;

  /** The Vietnam time every test starts at; tests move it forward like the legacy suite. */
  private String[] vnNow = {"2027-02-22", "10:00"}; // Monday of week N-1

  private Cookie hc;
  private Cookie employee;
  private Cookie colleague;
  private Cookie unassigned;
  private Cookie deptProd;
  private Cookie sys;

  @BeforeEach
  void atNow() throws Exception {
    setVnTime(vnNow[0], vnNow[1]);
    hc = signInReady(Demo.HC_ADMIN);
    employee = signInReady(Demo.EMPLOYEE);
    colleague = signInReady(COLLEAGUE);
    unassigned = signInReady(Demo.UNASSIGNED);
    deptProd = signInReady(Demo.DEPT_ADMIN_PRODUCTION);
    sys = signInReady(Demo.SYS_ADMIN);
  }

  private void moveTo(String date, String time) throws Exception {
    vnNow = new String[] {date, time};
    atNow();
  }

  @AfterAll
  void cleanUp() {
    // Leave nothing behind that other classes count (the seed test counts canteens).
    if (otherCanteen == null) {
      return;
    }
    jdbc.sql("UPDATE \"OrgUnit\" SET \"canteenId\" = NULL WHERE id = 'ou_IT'").update();
    String days =
        "SELECT d.id FROM \"MenuDay\" d JOIN \"MenuWeek\" w ON w.id = d.\"menuWeekId\""
            + " WHERE w.\"canteenId\" = :c";
    jdbc.sql("DELETE FROM \"MenuDayDessert\" WHERE \"menuDayId\" IN (" + days + ")")
        .param("c", otherCanteen)
        .update();
    jdbc.sql(
            "UPDATE \"MenuItem\" SET \"replacedByItemId\" = NULL WHERE \"menuDayId\" IN ("
                + days
                + ")")
        .param("c", otherCanteen)
        .update();
    jdbc.sql("DELETE FROM \"MenuItem\" WHERE \"menuDayId\" IN (" + days + ")")
        .param("c", otherCanteen)
        .update();
    jdbc.sql(
            "DELETE FROM \"MenuDay\" WHERE \"menuWeekId\" IN"
                + " (SELECT id FROM \"MenuWeek\" WHERE \"canteenId\" = :c)")
        .param("c", otherCanteen)
        .update();
    for (String table : List.of("MenuWeek", "Dish", "Dessert")) {
      jdbc.sql("DELETE FROM \"" + table + "\" WHERE \"canteenId\" = :c")
          .param("c", otherCanteen)
          .update();
    }
    jdbc.sql("DELETE FROM \"Canteen\" WHERE id = :c").param("c", otherCanteen).update();
  }

  // --- request bodies --------------------------------------------------------------------------

  private String day(String date, List<String> dishKeys, List<String> dessertKeys) {
    List<String> items = dishKeys.stream().map(k -> "{'dishId':'" + dish.get(k) + "'}").toList();
    List<String> desserts = dessertKeys.stream().map(k -> "'" + dessert.get(k) + "'").toList();
    return "{'date':'%s','isServing':true,'items':[%s],'dessertIds':[%s]}"
        .formatted(date, String.join(",", items), String.join(",", desserts));
  }

  private String day(String date, List<String> dishKeys) {
    return day(date, dishKeys, List.of());
  }

  private static String off(String date) {
    return "{'date':'%s','isServing':false,'items':[],'dessertIds':[]}".formatted(date);
  }

  private static String week(String canteenId, String weekStart, String... days) {
    return "{'canteenId':'%s','weekStart':'%s','days':[%s]}"
        .formatted(canteenId, weekStart, String.join(",", days));
  }

  private static String dishBody(String name, String type, String canteenId) {
    return "{'canteenId':'%s','name':'%s','typeId':'%s','sessions':['LUNCH']}"
        .formatted(canteenId, name, TYPE.get(type));
  }

  private static String dishBody(String name, String type) {
    return dishBody(name, type, CANTEEN);
  }

  private String adminWeek(String canteenId, String weekStart) throws Exception {
    return read(hc, "/api/admin/menu/weeks?canteenId=" + canteenId + "&weekStart=" + weekStart);
  }

  /**
   * The audit actions of one entity in the order they were written. The clock is frozen, so they
   * share one timestamp; AuditLog is append-only, so its physical order is the insertion order.
   */
  private static final String AUDIT_ACTIONS =
      "SELECT action FROM \"AuditLog\" WHERE \"entityId\" = :id ORDER BY ctid";

  private int count(String sql) {
    return jdbc.sql(sql).query(Integer.class).single();
  }

  // --- BR-10: dish and dessert libraries -----------------------------------------------------

  @Test
  @Order(1)
  @DisplayName(
      "BR-10: the HC admin adds main dishes with a type and sessions, shown as \"[Type] Name\"")
  void addDishes() throws Exception {
    String[][] defs = {
      {"chay", "Đậu hũ sốt cà", "CHAY"},
      {"chao", "Cháo gà", "CHAO"},
      {"ca", "Cá kho tộ", "MAN"},
      {"thit", "Thịt kho trứng", "MAN"},
      {"ga", "Gà chiên mắm", "MAN"},
      {"suon", "Sườn ram", "MAN"},
      {"old", "Món cũ", "MAN"}
    };
    for (String[] d : defs) {
      String res = expect(201, hc, HttpMethod.POST, "/api/admin/dishes", dishBody(d[1], d[2]));
      dish.put(d[0], JsonPath.read(res, "$.id"));
    }
    String list = read(hc, "/api/admin/dishes?canteenId=" + CANTEEN);
    Map<String, Object> ca =
        JsonPath.<List<Map<String, Object>>>read(
                list, "$.items[?(@.id == '" + dish.get("ca") + "')]")
            .getFirst();
    assertThat(ca)
        .containsEntry("displayName", "[Mặn] Cá kho tộ")
        .containsEntry("type", Map.of("id", TYPE.get("MAN"), "name", "Mặn", "color", "#b91c1c"))
        .containsEntry("sessions", List.of("LUNCH"))
        .containsEntry("archivedAt", null);
    for (String[] d :
        new String[][] {{"chuoi", "Chuối"}, {"raucau", "Rau câu"}, {"sua", "Sữa chua"}}) {
      String res =
          expect(
              201,
              hc,
              HttpMethod.POST,
              "/api/admin/desserts",
              "{'canteenId':'" + CANTEEN + "','name':'" + d[1] + "'}");
      dessert.put(d[0], JsonPath.read(res, "$.id"));
    }
  }

  @Test
  @Order(2)
  @DisplayName("BR-10: the same dish cannot be added twice, whatever its case or spacing")
  void noDuplicates() throws Exception {
    String res =
        expect(409, hc, HttpMethod.POST, "/api/admin/dishes", dishBody("  cá KHO   tộ ", "MAN"));
    assertThat(JsonPath.<String>read(res, "$.code")).isEqualTo("CONFLICT");
    assertThat(JsonPath.<String>read(res, "$.details[0].message")).isEqualTo("DUPLICATE");
    // Two identical requests at once: only one is stored.
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      Callable<Integer> post =
          () ->
              send(hc, HttpMethod.POST, "/api/admin/dishes", json(dishBody("Canh chua", "MAN")))
                  .getStatus();
      Future<Integer> a = pool.submit(post);
      Future<Integer> b = pool.submit(post);
      assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(201, 409);
    }
    assertThat(count("SELECT count(*) FROM \"Dish\" WHERE name = 'Canh chua'")).isEqualTo(1);
    expect(
        409,
        hc,
        HttpMethod.POST,
        "/api/admin/desserts",
        "{'canteenId':'" + CANTEEN + "','name':'chuối'}");
    // Another type is another dish; a name is stored trimmed with single spaces.
    String chay =
        expect(201, hc, HttpMethod.POST, "/api/admin/dishes", dishBody(" Cá   kho tộ", "CHAY"));
    assertThat(JsonPath.<String>read(chay, "$.name")).isEqualTo("Cá kho tộ");
    String chayId = JsonPath.read(chay, "$.id");
    expect(200, hc, HttpMethod.POST, "/api/admin/dishes/" + chayId + "/archive", null);
    // An archived copy still counts: restore it instead of adding it again.
    String again =
        expect(409, hc, HttpMethod.POST, "/api/admin/dishes", dishBody("cá kho tộ", "CHAY"));
    assertThat(JsonPath.<Map<String, Object>>read(again, "$.details[0]"))
        .containsEntry("message", "DUPLICATE_ARCHIVED")
        .containsEntry("id", chayId);
  }

  @Test
  @Order(3)
  @DisplayName("BR-10: each canteen has its own library")
  void perCanteen() throws Exception {
    expect(400, hc, HttpMethod.GET, "/api/admin/dishes", null); // canteenId is required
    expect(404, hc, HttpMethod.GET, "/api/admin/dishes?canteenId=nope", null);
    otherCanteen = Ids.newId(clock.now().toEpochMilli());
    jdbc.sql(
            """
            INSERT INTO "Canteen" (id, "factoryId", code, name, "supplierId")
            VALUES (:id, 'fac_gv', 'CT-TEST', 'Nhà ăn thử', 'sup_gv')
            """)
        .param("id", otherCanteen)
        .update();
    // The same name is fine in another canteen's library.
    String res =
        expect(
            201,
            hc,
            HttpMethod.POST,
            "/api/admin/dishes",
            dishBody("Cá kho tộ", "MAN", otherCanteen));
    dish.put("otherCa", JsonPath.read(res, "$.id"));
    dessert.put(
        "otherChuoi",
        JsonPath.read(
            expect(
                201,
                hc,
                HttpMethod.POST,
                "/api/admin/desserts",
                "{'canteenId':'" + otherCanteen + "','name':'Chuối'}"),
            "$.id"));
    assertThat(
            JsonPath.<List<String>>read(
                read(hc, "/api/admin/dishes?canteenId=" + otherCanteen), "$.items[*].id"))
        .containsExactly(dish.get("otherCa"));
    // A dish of another canteen cannot go on this canteen's menu.
    expect(
        400,
        hc,
        HttpMethod.PUT,
        "/api/admin/menu/weeks",
        week(CANTEEN, WEEK, day("2027-03-01", List.of("otherCa"))));
  }

  @Test
  @Order(4)
  @DisplayName("BR-10: name, type and at least one session are required")
  void required() throws Exception {
    expect(400, hc, HttpMethod.POST, "/api/admin/dishes", dishBody("", "MAN"));
    expect(
        400,
        hc,
        HttpMethod.POST,
        "/api/admin/dishes",
        "{'canteenId':'" + CANTEEN + "','name':'X','typeId':'dt_nope','sessions':['LUNCH']}");
    expect(
        400,
        hc,
        HttpMethod.POST,
        "/api/admin/dishes",
        "{'canteenId':'" + CANTEEN + "','name':'X','typeId':'dt_fac_gv_MAN','sessions':[]}");
    expect(
        400,
        hc,
        HttpMethod.POST,
        "/api/admin/desserts",
        "{'canteenId':'" + CANTEEN + "','name':' '}");
  }

  @Test
  @Order(5)
  @DisplayName(
      "BR-10: edits are audited; archived dishes leave the default list but are never deleted")
  void archive() throws Exception {
    String old = dish.get("old");
    expect(
        200,
        hc,
        HttpMethod.PUT,
        "/api/admin/dishes/" + old,
        "{'name':'Món cũ (bỏ)','typeId':'" + TYPE.get("MAN") + "','sessions':['LUNCH']}");
    expect(200, hc, HttpMethod.POST, "/api/admin/dishes/" + old + "/archive", null);
    assertThat(
            JsonPath.<List<String>>read(
                read(hc, "/api/admin/dishes?canteenId=" + CANTEEN), "$.items[*].id"))
        .doesNotContain(old);
    String all = read(hc, "/api/admin/dishes?canteenId=" + CANTEEN + "&includeArchived=true");
    assertThat(JsonPath.<List<String>>read(all, "$.items[?(@.id == '" + old + "')].archivedAt"))
        .singleElement()
        .isNotNull();
    assertThat(count("SELECT count(*) FROM \"Dish\" WHERE id = '" + old + "'")).isEqualTo(1);
    assertThat(jdbc.sql(AUDIT_ACTIONS).param("id", old).query(String.class).list())
        .containsExactly("dish.created", "dish.updated", "dish.archived");
  }

  // --- BR-10 / BR-13: dish types are master data ---------------------------------------------

  @Test
  @Order(10)
  @DisplayName(
      "BR-10: the HC admin adds a type with a colour; it is shown with dishes and has no bounds"
          + " by default")
  void dishTypes() throws Exception {
    String created =
        expect(
            201,
            hc,
            HttpMethod.POST,
            "/api/admin/dish-types",
            "{'code':'lowcarb','name':'Low-carb','color':'#7C3AED','sortOrder':9}");
    String id = JsonPath.read(created, "$.id");
    String list = read(hc, "/api/admin/dish-types");
    Map<String, Object> lowcarb =
        JsonPath.<List<Map<String, Object>>>read(list, "$.items[?(@.id == '" + id + "')]")
            .getFirst();
    assertThat(lowcarb)
        .containsEntry("code", "LOWCARB")
        .containsEntry("color", "#7c3aed")
        .containsEntry("minPerDay", null)
        .containsEntry("maxPerDay", null)
        .containsEntry("active", true);
    assertThat(JsonPath.<List<String>>read(list, "$.items[*].code").subList(0, 3))
        .containsExactly("MAN", "CHAY", "CHAO");
    expect(
        409,
        hc,
        HttpMethod.POST,
        "/api/admin/dish-types",
        "{'code':'LOWCARB','name':'X','color':'#000000'}");
    expect(
        400,
        hc,
        HttpMethod.POST,
        "/api/admin/dish-types",
        "{'code':'X1','name':'X','color':'red'}");
    expect(
        400, hc, HttpMethod.PATCH, "/api/admin/dish-types/" + id, "{'minPerDay':2,'maxPerDay':1}");
    expect(
        200,
        hc,
        HttpMethod.PATCH,
        "/api/admin/dish-types/" + id,
        "{'name':'Eat clean / Low-carb','active':false}");
    // An inactive type takes no new dishes.
    expect(
        400,
        hc,
        HttpMethod.POST,
        "/api/admin/dishes",
        "{'canteenId':'"
            + CANTEEN
            + "','name':'Ức gà','typeId':'"
            + id
            + "','sessions':['LUNCH']}");
    assertThat(jdbc.sql(AUDIT_ACTIONS).param("id", id).query(String.class).list())
        .containsExactly("dishType.created", "dishType.updated");
  }

  // --- building and publishing a week (BR-08, BR-11, BR-13, BR-03) ---------------------------

  @Test
  @Order(20)
  @DisplayName(
      "BR-08: a new week offers Monday to Sunday, serving Monday to Friday, with the expected"
          + " closeAt")
  void newWeek() throws Exception {
    String res = adminWeek(CANTEEN, WEEK);
    assertThat(JsonPath.<String>read(res, "$.status")).isEqualTo("NEW");
    assertThat(JsonPath.<Object>read(res, "$.id")).isNull();
    assertThat(JsonPath.<List<Boolean>>read(res, "$.days[*].isServing"))
        .containsExactly(true, true, true, true, true, false, false);
    assertThat(JsonPath.<String>read(res, "$.days[6].date")).isEqualTo("2027-03-07");
    assertThat(JsonPath.<Boolean>read(res, "$.closeAtIsExpected")).isTrue();
    assertThat(JsonPath.<String>read(res, "$.closeAt")).isEqualTo("2027-02-25T17:00:00.000Z");
  }

  @Test
  @Order(21)
  @DisplayName("BR-10: an archived dish cannot be put on a menu")
  void archivedNotOnMenu() throws Exception {
    expect(
        400,
        hc,
        HttpMethod.PUT,
        "/api/admin/menu/weeks",
        week(CANTEEN, WEEK, day("2027-03-01", List.of("old"))));
  }

  @Test
  @Order(22)
  @DisplayName(
      "BR-11 / BR-08 / BR-13: saves a draft with desserts, Saturday on, and warns about an unusual"
          + " mix")
  void saveDraft() throws Exception {
    List<String> plusGa = new ArrayList<>(STANDARD);
    plusGa.add("ga");
    String res =
        expect(
            200,
            hc,
            HttpMethod.PUT,
            "/api/admin/menu/weeks",
            week(
                CANTEEN,
                WEEK,
                day("2027-03-01", STANDARD, List.of("chuoi")),
                day("2027-03-02", plusGa, List.of("raucau")),
                day("2027-03-03", List.of("ca")),
                day("2027-03-04", STANDARD),
                day("2027-03-05", STANDARD, List.of("sua")),
                day("2027-03-06", List.of("chay", "ca", "thit"), List.of("chuoi"))));
    weekId = JsonPath.read(res, "$.menu.id");
    assertThat(JsonPath.<String>read(res, "$.menu.status")).isEqualTo("DRAFT");
    List<Map<String, Object>> days = JsonPath.read(res, "$.menu.days");
    assertThat(
            days.stream()
                .map(
                    d ->
                        d.get("mix") == null
                            ? null
                            : (Object) ((Map<?, ?>) d.get("mix")).get("usual"))
                .toList())
        .containsExactly(true, true, false, true, true, false, null);
    assertThat(JsonPath.<List<String>>read(res, "$.menu.days[2].mix.issues[*].typeName"))
        .containsExactly("Mặn", "Chay", "Cháo");
    assertThat(JsonPath.<Boolean>read(res, "$.menu.days[5].isServing")).isTrue();
    assertThat(JsonPath.<List<Map<String, Object>>>read(res, "$.menu.days[0].desserts"))
        .containsExactly(Map.of("id", dessert.get("chuoi"), "name", "Chuối"));
    assertThat(JsonPath.<List<String>>read(res, "$.menu.days[0].items[*].displayName"))
        .containsExactly(
            "[Chay] Đậu hũ sốt cà", "[Cháo] Cháo gà", "[Mặn] Cá kho tộ", "[Mặn] Thịt kho trứng");
    assertThat(JsonPath.<Map<String, Object>>read(res, "$.effects"))
        .isEqualTo(
            Map.of("movedRegistrations", 0, "flaggedRegistrations", 0, "notifiedEmployees", 0));
  }

  @Test
  @Order(23)
  @DisplayName("BR-11: the same dish twice on a day and a non-serving day with dishes are rejected")
  void rejected() throws Exception {
    expect(
        400,
        hc,
        HttpMethod.PUT,
        "/api/admin/menu/weeks",
        week(CANTEEN, WEEK, day("2027-03-04", List.of("ca", "ca"))));
    expect(
        400,
        hc,
        HttpMethod.PUT,
        "/api/admin/menu/weeks",
        week(
            CANTEEN,
            WEEK,
            day("2027-03-04", List.of("ca")).replace("'isServing':true", "'isServing':false")));
    // not a Monday
    expect(
        400,
        hc,
        HttpMethod.PUT,
        "/api/admin/menu/weeks",
        week(CANTEEN, "2027-03-02", off("2027-03-02")));
  }

  @Test
  @Order(24)
  @DisplayName("BR-02: employees never see a draft")
  void neverDraft() throws Exception {
    assertThat(
            JsonPath.<Map<String, Object>>read(
                read(employee, "/api/menu/my-week?weekStart=" + WEEK), "$"))
        .isEqualTo(
            Map.of("state", "NOT_PUBLISHED", "weekStart", WEEK, "canteenName", "Nhà ăn Gò Vấp"));
  }

  @Test
  @Order(25)
  @DisplayName("a week with a serving day lacking a main dish or a dessert cannot be published")
  void incomplete() throws Exception {
    String res =
        expect(409, hc, HttpMethod.POST, "/api/admin/menu/weeks/" + weekId + "/publish", null);
    assertThat(JsonPath.<String>read(res, "$.code")).isEqualTo("MENU_INCOMPLETE");
    assertThat(JsonPath.<List<Map<String, Object>>>read(res, "$.details"))
        .containsExactly(
            Map.of("date", "2027-03-03", "gaps", List.of("DESSERT")),
            Map.of("date", "2027-03-04", "gaps", List.of("DESSERT")));
    String direct =
        expect(
            409,
            hc,
            HttpMethod.POST,
            "/api/admin/menu/weeks/publish",
            week(CANTEEN, WEEK, day("2027-03-03", List.of(), List.of("chuoi"))));
    assertThat(JsonPath.<List<Map<String, Object>>>read(direct, "$.details"))
        .containsExactly(Map.of("date", "2027-03-03", "gaps", List.of("MAIN")));
    assertThat(
            jdbc.sql("SELECT status::text FROM \"MenuWeek\" WHERE id = :id")
                .param("id", weekId)
                .query(String.class)
                .single())
        .isEqualTo("DRAFT");
  }

  @Test
  @Order(26)
  @DisplayName("BR-03: publishing on Monday of week N-1 fixes closeAt at the regular 00:00 Friday")
  void publish() throws Exception {
    // Publishing straight from the builder saves the submitted days first (no draft step).
    String res =
        expect(
            200,
            hc,
            HttpMethod.POST,
            "/api/admin/menu/weeks/publish",
            week(
                CANTEEN,
                WEEK,
                day("2027-03-03", List.of("ca"), List.of("raucau")),
                day("2027-03-04", STANDARD, List.of("chuoi"))));
    assertThat(JsonPath.<String>read(res, "$.status")).isEqualTo("PUBLISHED");
    assertThat(JsonPath.<String>read(res, "$.closeAt")).isEqualTo("2027-02-25T17:00:00.000Z");
    assertThat(JsonPath.<Boolean>read(res, "$.closeAtIsExpected")).isFalse();
    assertThat(JsonPath.<Boolean>read(res, "$.latePublish")).isFalse();
    String after =
        jdbc.sql(
                """
                SELECT after::text FROM "AuditLog" WHERE action = 'menu.published'
                  AND "entityId" = :id
                """)
            .param("id", weekId)
            .query(String.class)
            .single();
    assertThat(JsonPath.<String>read(after, "$.closeAt")).isEqualTo("2027-02-25T17:00:00.000Z");
    assertThat(JsonPath.<Boolean>read(after, "$.latePublish")).isFalse();
  }

  @Test
  @Order(27)
  @DisplayName("publishing twice is refused")
  void publishTwice() throws Exception {
    String res =
        expect(409, hc, HttpMethod.POST, "/api/admin/menu/weeks/" + weekId + "/publish", null);
    assertThat(JsonPath.<String>read(res, "$.code")).isEqualTo("INVALID_STATE");
  }

  // --- employees see their canteen's published menu (BR-02, BR-15, BR-63) --------------------

  @Test
  @Order(30)
  @DisplayName("BR-02: after publishing the employee sees the serving days, meals and desserts")
  void employeeSees() throws Exception {
    String res = read(employee, "/api/menu/my-week?weekStart=" + WEEK);
    assertThat(JsonPath.<String>read(res, "$.state")).isEqualTo("PUBLISHED");
    assertThat(JsonPath.<String>read(res, "$.menu.canteenName")).isEqualTo("Nhà ăn Gò Vấp");
    assertThat(JsonPath.<String>read(res, "$.menu.closeAt")).isEqualTo("2027-02-25T17:00:00.000Z");
    assertThat(JsonPath.<List<String>>read(res, "$.menu.days[*].date"))
        .containsExactly(
            "2027-03-01", "2027-03-02", "2027-03-03", "2027-03-04", "2027-03-05", "2027-03-06");
    assertThat(JsonPath.<List<Map<String, Object>>>read(res, "$.menu.days[2].desserts"))
        .containsExactly(Map.of("id", dessert.get("raucau"), "name", "Rau câu"));
    assertThat(JsonPath.<List<String>>read(res, "$.menu.days[0].items[*].displayName"))
        .contains("[Mặn] Cá kho tộ");
    // the mix warning is for the HC admin only
    assertThat(JsonPath.<Object>read(res, "$.menu.days[0].mix")).isNull();
  }

  @Test
  @Order(31)
  @DisplayName("defaults to the coming week")
  void defaultsToComingWeek() throws Exception {
    assertThat(JsonPath.<String>read(read(employee, "/api/menu/my-week"), "$.menu.weekStart"))
        .isEqualTo(WEEK);
  }

  @Test
  @Order(32)
  @DisplayName("BR-60 / BR-63: an employee on the \"unassigned\" list sees no menu")
  void unassignedSeesNothing() throws Exception {
    assertThat(
            JsonPath.<Map<String, Object>>read(
                read(unassigned, "/api/menu/my-week?weekStart=" + WEEK), "$"))
        .isEqualTo(Map.of("state", "UNASSIGNED", "weekStart", WEEK));
  }

  @Test
  @Order(33)
  @DisplayName("BR-15: menus are per canteen; an employee sees only their meal unit's canteen")
  void perCanteenMenus() throws Exception {
    // A second canteen (created with its library above), mapped onto Phòng IT (where the system
    // admin works).
    String otherWeek = "2027-03-15";
    String saved =
        expect(
            200,
            hc,
            HttpMethod.PUT,
            "/api/admin/menu/weeks",
            week(
                otherCanteen,
                otherWeek,
                day("2027-03-15", List.of("otherCa"), List.of("otherChuoi"))));
    expect(
        200,
        hc,
        HttpMethod.POST,
        "/api/admin/menu/weeks/" + JsonPath.read(saved, "$.menu.id") + "/publish",
        null);
    jdbc.sql("UPDATE \"OrgUnit\" SET \"canteenId\" = :c WHERE id = 'ou_IT'")
        .param("c", otherCanteen)
        .update();

    String itStaff = read(sys, "/api/menu/my-week?weekStart=" + otherWeek);
    assertThat(JsonPath.<String>read(itStaff, "$.state")).isEqualTo("PUBLISHED");
    assertThat(JsonPath.<String>read(itStaff, "$.menu.canteenName")).isEqualTo("Nhà ăn thử");
    String production = read(employee, "/api/menu/my-week?weekStart=" + otherWeek);
    assertThat(JsonPath.<String>read(production, "$.state")).isEqualTo("NOT_PUBLISHED");
    assertThat(JsonPath.<String>read(production, "$.canteenName")).isEqualTo("Nhà ăn Gò Vấp");
  }

  // --- BR-14: editing a published menu --------------------------------------------------------

  private String itemOf(List<Map<String, Object>> items, String key) {
    return items.stream()
        .filter(i -> dish.get(key).equals(i.get("dishId")))
        .map(i -> (String) i.get("id"))
        .findFirst()
        .orElseThrow();
  }

  private void register(String employeeCode, String itemId, int quantity) {
    jdbc.sql(
            """
            INSERT INTO "Registration" (id, "employeeId", "menuItemId", date, quantity, source,
                "updatedAt")
            VALUES (:id, :e, :item, DATE '2027-03-01', :q, 'SELF', now())
            """)
        .param("id", Ids.newId(clock.now().toEpochMilli()))
        .param("e", employeeId(employeeCode))
        .param("item", itemId)
        .param("q", quantity)
        .update();
  }

  @Test
  @Order(40)
  @DisplayName(
      "BR-14: replacing a dish moves its registrations; removing one flags them; both notify")
  void editPublished() throws Exception {
    List<Map<String, Object>> monday = JsonPath.read(adminWeek(CANTEEN, WEEK), "$.days[0].items");
    String ca = itemOf(monday, "ca");
    String thit = itemOf(monday, "thit");
    register(Demo.EMPLOYEE, ca, 2);
    register(Demo.EMPLOYEE, thit, 1);
    register(COLLEAGUE, ca, 1);

    List<String> items = new ArrayList<>();
    for (Map<String, Object> i : monday) {
      if (i.get("id").equals(thit)) {
        continue;
      }
      String dishId = i.get("id").equals(ca) ? dish.get("suon") : (String) i.get("dishId");
      items.add("{'id':'" + i.get("id") + "','dishId':'" + dishId + "'}");
    }
    String res =
        expect(
            200,
            hc,
            HttpMethod.PUT,
            "/api/admin/menu/weeks",
            "{'canteenId':'%s','weekStart':'%s','reason':'NCC đổi món','days':[{'date':'2027-03-01','isServing':true,'items':[%s],'dessertIds':['%s']}]}"
                .formatted(CANTEEN, WEEK, String.join(",", items), dessert.get("chuoi")));
    assertThat(JsonPath.<Map<String, Object>>read(res, "$.effects"))
        .isEqualTo(
            Map.of("movedRegistrations", 2, "flaggedRegistrations", 1, "notifiedEmployees", 2));

    Map<String, Object> oldItem =
        jdbc.sql("SELECT \"removedAt\", \"replacedByItemId\" FROM \"MenuItem\" WHERE id = :id")
            .param("id", ca)
            .query()
            .singleRow();
    assertThat(oldItem.get("removedAt")).isNotNull();
    assertThat(oldItem.get("replacedByItemId")).isNotNull();
    List<Map<String, Object>> moved =
        jdbc.sql(
                """
                SELECT quantity, status::text, "replacedFromItemId" FROM "Registration"
                WHERE "menuItemId" = :id ORDER BY quantity
                """)
            .param("id", oldItem.get("replacedByItemId"))
            .query()
            .listOfRows();
    assertThat(moved).extracting(r -> r.get("quantity")).containsExactly(1, 2);
    assertThat(moved)
        .allSatisfy(
            r ->
                assertThat(r)
                    .containsEntry("status", "ACTIVE")
                    .containsEntry("replacedFromItemId", ca));
    assertThat(
            jdbc.sql("SELECT status::text FROM \"Registration\" WHERE \"menuItemId\" = :id")
                .param("id", thit)
                .query(String.class)
                .list())
        .containsExactly("NEEDS_RESELECT");
    assertThat(
            jdbc.sql(
                    """
                    SELECT reason FROM "AuditLog" WHERE action = 'menu.edited' AND "entityId" = :id
                    """)
                .param("id", weekId)
                .query(String.class)
                .single())
        .isEqualTo("NCC đổi món");
  }

  @Test
  @Order(41)
  @DisplayName("BR-14: the employee sees in-app notices and can dismiss only their own")
  void notices() throws Exception {
    List<Map<String, Object>> notices =
        JsonPath.read(read(employee, "/api/me/banners"), "$.items[?(@.kind == 'NOTIFICATION')]");
    assertThat(notices)
        .extracting(n -> (String) n.get("notificationType"))
        .containsExactlyInAnyOrder("MENU_DISH_REMOVED", "MENU_DISH_REPLACED");
    Map<String, Object> replaced =
        notices.stream()
            .filter(n -> n.get("notificationType").equals("MENU_DISH_REPLACED"))
            .findFirst()
            .orElseThrow();
    assertThat((Map<String, Object>) replaced.get("params"))
        .containsEntry("date", "2027-03-01")
        .containsEntry("from", "[Mặn] Cá kho tộ")
        .containsEntry("to", "[Mặn] Sườn ram")
        .containsEntry("weekStart", WEEK)
        .containsEntry("canteen", "Nhà ăn Gò Vấp");
    assertThat(replaced).containsEntry("severity", "info");
    Map<String, Object> removed =
        notices.stream()
            .filter(n -> n.get("notificationType").equals("MENU_DISH_REMOVED"))
            .findFirst()
            .orElseThrow();
    assertThat((Map<String, Object>) removed.get("params")).containsEntry("lockTime", "08:00");

    // A colleague cannot dismiss someone else's notice.
    String id = (String) replaced.get("notificationId");
    expect(404, colleague, HttpMethod.POST, "/api/me/notifications/" + id + "/read", null);
    assertThat(
            expect(200, employee, HttpMethod.POST, "/api/me/notifications/" + id + "/read", null))
        .isEqualTo("{\"id\":\"" + id + "\"}");
    assertThat(JsonPath.<List<Object>>read(read(employee, "/api/me/banners"), "$.items"))
        .hasSize(notices.size() - 1);
  }

  private String keep(Map<String, Object> day, String extraDishId) {
    List<String> items = new ArrayList<>();
    for (Map<String, Object> i : (List<Map<String, Object>>) day.get("items")) {
      items.add("{'id':'" + i.get("id") + "','dishId':'" + i.get("dishId") + "'}");
    }
    if (extraDishId != null) {
      items.add("{'dishId':'" + extraDishId + "'}");
    }
    List<String> desserts = new ArrayList<>();
    for (Map<String, Object> x : (List<Map<String, Object>>) day.get("desserts")) {
      desserts.add("'" + x.get("id") + "'");
    }
    return "{'date':'%s','isServing':%s,'items':[%s],'dessertIds':[%s]}"
        .formatted(
            day.get("date"),
            day.get("isServing"),
            String.join(",", items),
            String.join(",", desserts));
  }

  @Test
  @Order(42)
  @DisplayName("BR-14: once a day has locked (08:00) it can no longer change; later days still can")
  void lockedDay() throws Exception {
    moveTo("2027-03-01", "08:00");
    String current = adminWeek(CANTEEN, WEEK);
    assertThat(JsonPath.<List<String>>read(current, "$.lockedDates")).containsExactly("2027-03-01");
    Map<String, Object> monday = JsonPath.read(current, "$.days[0]");
    String res =
        expect(
            409,
            hc,
            HttpMethod.PUT,
            "/api/admin/menu/weeks",
            week(CANTEEN, WEEK, keep(monday, dish.get("ga"))));
    assertThat(JsonPath.<String>read(res, "$.code")).isEqualTo("INVALID_STATE");

    Map<String, Object> wednesday = JsonPath.read(current, "$.days[2]");
    expect(
        200,
        hc,
        HttpMethod.PUT,
        "/api/admin/menu/weeks",
        week(CANTEEN, WEEK, keep(wednesday, dish.get("chay"))));
  }

  @Test
  @Order(43)
  @DisplayName("BR-08: a published serving day cannot be turned off")
  void cannotTurnOff() throws Exception {
    String res =
        expect(
            409,
            hc,
            HttpMethod.PUT,
            "/api/admin/menu/weeks",
            week(CANTEEN, WEEK, off("2027-03-04")));
    assertThat(JsonPath.<String>read(res, "$.code")).isEqualTo("INVALID_STATE");
  }

  // --- BR-04: late publish ----------------------------------------------------------------------

  @Test
  @Order(50)
  @DisplayName(
      "BR-04: a menu published on Saturday after the regular close gets closeAt = publish + 24 h")
  void latePublish() throws Exception {
    // Saturday; week 2027-03-08 closed regularly Friday 00:00
    moveTo("2027-03-06", "09:30");
    String saved =
        expect(
            200,
            hc,
            HttpMethod.PUT,
            "/api/admin/menu/weeks",
            week(CANTEEN, "2027-03-08", day("2027-03-08", STANDARD, List.of("chuoi"))));
    assertThat(JsonPath.<Boolean>read(saved, "$.menu.latePublish")).isTrue();
    String res =
        expect(
            200,
            hc,
            HttpMethod.POST,
            "/api/admin/menu/weeks/" + JsonPath.read(saved, "$.menu.id") + "/publish",
            null);
    // Sunday 09:30 Vietnam time
    assertThat(JsonPath.<String>read(res, "$.closeAt")).isEqualTo("2027-03-07T02:30:00.000Z");
    assertThat(JsonPath.<Boolean>read(res, "$.latePublish")).isTrue();
  }

  // --- BR-10: Excel import of a canteen library -------------------------------------------------

  private static final List<String> HEADER =
      List.of("Nhóm (Món chính / Tráng miệng)", "Mã loại món (món chính)", "Tên món");

  private MockHttpServletResponse uploadLibrary(byte[] file, boolean apply) throws Exception {
    MockHttpServletResponse res =
        mvc.perform(
                multipart("/api/admin/dish-library/import?canteenId=" + CANTEEN + "&apply=" + apply)
                    .file(
                        new MockMultipartFile("file", "thu-vien.xlsx", Xlsx.XLSX.toString(), file))
                    .cookie(hc))
            .andReturn()
            .getResponse();
    res.setCharacterEncoding("UTF-8");
    return res;
  }

  private String uploadLibraryOk(byte[] file, boolean apply) throws Exception {
    MockHttpServletResponse res = uploadLibrary(file, apply);
    assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
    return res.getContentAsString();
  }

  @Test
  @Order(60)
  @DisplayName("BR-10: the template lists the active dish-type codes")
  void template() throws Exception {
    MockHttpServletResponse res =
        send(hc, HttpMethod.GET, "/api/admin/dish-library/template", null);
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(res.getHeader("Content-Disposition")).contains("mau-thu-vien-mon.xlsx");
    byte[] file = res.getContentAsByteArray();
    assertThat(TestXlsx.read(file, 0, 1).getFirst()).containsExactly(HEADER.getFirst());
    List<String> codes = TestXlsx.read(file, 1, 1).stream().skip(1).map(List::getFirst).toList();
    assertThat(codes).contains("MAN", "CHAY", "CHAO");
    try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
      List<String> lists = new ArrayList<>();
      for (DataValidation v : wb.getSheetAt(0).getDataValidations()) {
        var range = v.getRegions().getCellRangeAddress(0);
        String formula =
            v.getValidationConstraint().getExplicitListValues() != null
                ? String.join(",", v.getValidationConstraint().getExplicitListValues())
                : v.getValidationConstraint().getFormula1();
        lists.add(range.formatAsString() + " " + formula);
      }
      assertThat(lists)
          .containsExactlyInAnyOrder(
              "A2:A2001 Món chính,Tráng miệng",
              "B2:B2001 'Loại món'!$A$2:$A$" + (codes.size() + 1));
    }
  }

  @Test
  @Order(61)
  @DisplayName("BR-10: previews, then adds only what the library does not have yet")
  void importLibrary() throws Exception {
    byte[] file =
        TestXlsx.build(
            HEADER,
            List.of(
                List.of("Món chính", "MAN", "Cá kho tộ"), // already in the library
                List.of("Món chính", "chay", "Nấm kho tiêu"),
                List.of("Tráng miệng", "", "Thạch dừa")));
    String preview = uploadLibraryOk(file, false);
    assertThat(JsonPath.<String>read(preview, "$.status")).isEqualTo("PREVIEW");
    assertThat(JsonPath.<Integer>read(preview, "$.rowCount")).isEqualTo(3);
    assertThat(JsonPath.<Integer>read(preview, "$.alreadyInLibrary")).isEqualTo(1);
    assertThat(JsonPath.<List<String>>read(preview, "$.toCreate[*].name"))
        .containsExactly("Nấm kho tiêu", "Thạch dừa");
    assertThat(JsonPath.<List<Object>>read(preview, "$.toCreate[*].typeCode"))
        .containsExactly("CHAY", null);
    assertThat(count("SELECT count(*) FROM \"Dish\" WHERE name = 'Nấm kho tiêu'")).isZero();
    assertThat(JsonPath.<String>read(uploadLibraryOk(file, true), "$.status")).isEqualTo("APPLIED");
    assertThat(
            count(
                "SELECT count(*) FROM \"Dish\" WHERE name = 'Nấm kho tiêu' AND \"canteenId\" = 'ct_gv'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM \"Dessert\" WHERE name = 'Thạch dừa' AND \"canteenId\" = 'ct_gv'"))
        .isEqualTo(1);
    // The same file again adds nothing: no dish is ever added twice.
    String again = uploadLibraryOk(file, true);
    assertThat(JsonPath.<List<Object>>read(again, "$.toCreate")).isEmpty();
    assertThat(JsonPath.<Integer>read(again, "$.alreadyInLibrary")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM \"Dish\" WHERE name = 'Nấm kho tiêu'")).isEqualTo(1);
  }

  @Test
  @Order(62)
  @DisplayName("BR-10: one bad row rejects the file; a file that is not the template is refused")
  void importRejected() throws Exception {
    String res =
        uploadLibraryOk(
            TestXlsx.build(
                HEADER,
                List.of(
                    List.of("Món chính", "BBQ", "Bò nướng"),
                    List.of("Món chính", "MAN", "Gà rô ti"))),
            true);
    assertThat(JsonPath.<String>read(res, "$.status")).isEqualTo("REJECTED");
    assertThat(JsonPath.<List<Map<String, Object>>>read(res, "$.errors"))
        .containsExactly(
            Map.of("rowNumber", 2, "field", "typeCode", "code", "TYPE_UNKNOWN", "value", "BBQ"));
    assertThat(count("SELECT count(*) FROM \"Dish\" WHERE name = 'Gà rô ti'")).isZero();
    assertThat(
            uploadLibrary(TestXlsx.build(List.of("Khác"), List.of(List.of("x"))), false)
                .getStatus())
        .isEqualTo(400);
  }

  // --- 403 on every dish and menu admin endpoint ------------------------------------------------

  @Test
  @Order(70)
  @DisplayName(
      "an employee, a dept admin and a system admin get 403 (dish library and menu are HC only)")
  void forbidden() throws Exception {
    String id = "x";
    String body = dishBody("X", "MAN");
    String offWeek = week(CANTEEN, WEEK, off(WEEK));
    expectForbidden(
        List.of(employee, deptProd, sys),
        List.of(
            Endpoint.get("/api/admin/dishes?canteenId=" + CANTEEN),
            Endpoint.of(HttpMethod.POST, "/api/admin/dishes", body),
            Endpoint.of(HttpMethod.PUT, "/api/admin/dishes/" + id, body),
            Endpoint.of(HttpMethod.POST, "/api/admin/dishes/" + id + "/archive", null),
            Endpoint.of(HttpMethod.POST, "/api/admin/dishes/" + id + "/restore", null),
            Endpoint.get("/api/admin/desserts?canteenId=" + CANTEEN),
            Endpoint.of(
                HttpMethod.POST,
                "/api/admin/desserts",
                "{'canteenId':'" + CANTEEN + "','name':'X'}"),
            Endpoint.of(HttpMethod.PUT, "/api/admin/desserts/" + id, "{'name':'X'}"),
            Endpoint.of(HttpMethod.POST, "/api/admin/desserts/" + id + "/archive", null),
            Endpoint.of(HttpMethod.POST, "/api/admin/desserts/" + id + "/restore", null),
            Endpoint.get("/api/admin/menu/canteens"),
            Endpoint.get("/api/admin/menu/weeks?canteenId=" + CANTEEN + "&weekStart=" + WEEK),
            Endpoint.of(HttpMethod.PUT, "/api/admin/menu/weeks", offWeek),
            Endpoint.of(HttpMethod.POST, "/api/admin/menu/weeks/" + id + "/publish", null),
            Endpoint.of(HttpMethod.POST, "/api/admin/menu/weeks/publish", offWeek),
            Endpoint.get("/api/admin/dish-types"),
            Endpoint.of(
                HttpMethod.POST,
                "/api/admin/dish-types",
                "{'code':'X','name':'X','color':'#000000'}"),
            Endpoint.of(HttpMethod.PATCH, "/api/admin/dish-types/" + id, "{'name':'X'}"),
            Endpoint.get("/api/admin/dish-library/template"),
            Endpoint.of(
                HttpMethod.POST, "/api/admin/dish-library/import?canteenId=" + CANTEEN, null)));
  }

  @Test
  @Order(71)
  @DisplayName("the employee menu needs a session")
  void needsSession() throws Exception {
    expect(401, null, HttpMethod.GET, "/api/menu/my-week", null);
  }

  @Test
  @Order(72)
  @DisplayName("the HC admin's canteen list names the coming week")
  void canteens() throws Exception {
    String res = read(hc, "/api/admin/menu/canteens");
    assertThat(JsonPath.<List<String>>read(res, "$.items[*].code")).contains("CT-GV");
    assertThat(JsonPath.<String>read(res, "$.upcomingWeekStart")).isEqualTo("2027-03-08");
  }
}
