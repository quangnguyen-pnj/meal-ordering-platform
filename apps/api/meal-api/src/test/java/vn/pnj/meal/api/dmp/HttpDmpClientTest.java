package vn.pnj.meal.api.dmp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.api.config.AppProperties;
import vn.pnj.meal.rules.VnTime;

/** HttpDmpClient against a local fake DMP; never the real one (plan 7.1 rule 8). */
@DisplayName("HttpDmpClient")
class HttpDmpClientTest {
  private HttpServer server;
  private final List<String> calls = new CopyOnWriteArrayList<>();
  private final List<String> queries = new CopyOnWriteArrayList<>();

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private HttpDmpClient client() {
    return new HttpDmpClient(
        new AppProperties.Dmp(
            AppProperties.DmpMode.HTTP,
            "on-time",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/",
            "svc@example.test",
            "x",
            "PNJP"));
  }

  private void start(Handler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          calls.add(
              exchange.getRequestMethod()
                  + " "
                  + path
                  + " "
                  + exchange.getRequestHeaders().getFirst("Authorization"));
          String query = exchange.getRequestURI().getRawQuery();
          if (query != null) {
            queries.add(query);
          }
          Reply reply = handler.handle(query == null ? path : path + "?" + query);
          byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(reply.status(), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  private record Reply(int status, String body) {}

  private interface Handler {
    Reply handle(String path);
  }

  private static Reply login(String path) {
    return path.equals("/api/auth/login") ? new Reply(200, "{\"token\":\"t\"}") : null;
  }

  @Test
  @DisplayName("BR-61: reads the hierarchy tree, depth first, with fallback field names")
  void hierarchy() throws IOException {
    start(
        path -> {
          Reply auth = login(path);
          return auth != null
              ? auth
              : new Reply(
                  200,
                  """
                  {"code":"ROOT","name":"Công ty","levelName":"Công ty","children":[
                    {"unitCode":"A","unitName":"Khối A","type":"Khối","costCenter":"CC1",
                     "childs":[{"id":42}]},
                    {"code":" B ","children":null,"subUnits":[{"code":"B1","name":"  "}]}
                  ]}
                  """);
        });
    assertThat(client().getOrgTree())
        .containsExactly(
            new DmpClient.OrgNode("ROOT", "Công ty", null, "Công ty", null),
            new DmpClient.OrgNode("A", "Khối A", "ROOT", "Khối", "CC1"),
            new DmpClient.OrgNode("42", "42", "A", "Cấp 3", null),
            new DmpClient.OrgNode("B", "B", "ROOT", "Cấp 2", null),
            new DmpClient.OrgNode("B1", "B1", "B", "Cấp 3", null));
  }

  @Test
  @DisplayName("a hierarchy node without a code fails the read")
  void hierarchyWithoutCode() throws IOException {
    start(path -> login(path) != null ? login(path) : new Reply(200, "[{\"name\":\"x\"}]"));
    assertThatThrownBy(() -> client().getOrgTree())
        .isInstanceOf(DmpException.class)
        .hasMessageContaining("without a code");
  }

  @Test
  @DisplayName(
      "reads employees page by page, keeps only the allowed fields, parses both date forms")
  void employees() throws IOException {
    start(
        path -> {
          Reply auth = login(path);
          if (auth != null) {
            return auth;
          }
          return path.contains("page=0")
              ? new Reply(
                  200,
                  """
                  {"content":[
                    {"employeeId":"PNJP1","fullName":"An","email":"a@x.vn","unitCode":"A",
                     "costCenterCode":"CC1","startWDate":"2024-03-01T00:00:00","endWDate":null,
                     "phoneNumber":"0909","birthDate":"1990-01-01"},
                    {"employeeId":"PNJP2","unitCode":null},
                    {"employeeCode":"PNJP3","departmentCode":"B","startDate":"05-01-2023",
                     "endDate":"30-06-2026"}
                  ],"number":0,"totalPages":2,"last":false}
                  """)
              : new Reply(200, "{\"content\":[],\"number\":1,\"totalPages\":2}");
        });
    assertThat(client().getEmployees())
        .containsExactly(
            new DmpClient.Employee(
                "PNJP1", "An", "a@x.vn", "A", "CC1", LocalDate.of(2024, 3, 1), null),
            new DmpClient.Employee(
                "PNJP3",
                "PNJP3",
                null,
                "B",
                null,
                LocalDate.of(2023, 1, 5),
                LocalDate.of(2026, 6, 30)));
    assertThat(calls.stream().filter(c -> c.startsWith("GET")))
        .containsExactly("GET /api/hr-employees Bearer t", "GET /api/hr-employees Bearer t");
  }

  @Test
  @DisplayName("a response that is not a page fails the read")
  void notAPage() throws IOException {
    start(path -> login(path) != null ? login(path) : new Reply(200, "{\"items\":[]}"));
    assertThatThrownBy(() -> client().getEmployees())
        .isInstanceOf(DmpException.class)
        .hasMessageContaining("did not return a page");
  }

  @Test
  @DisplayName("logs in with the service account, then sends the bearer token")
  void bearer() throws IOException {
    start(
        path ->
            path.equals("/api/auth/login")
                ? new Reply(200, "{\"token\":\"t1\"}")
                : new Reply(200, "{\"ok\":true}"));
    assertThat(client().get("/api/hierarchy").get("ok").asBoolean()).isTrue();
    assertThat(calls).containsExactly("POST /api/auth/login null", "GET /api/hierarchy Bearer t1");
  }

  @Test
  @DisplayName("logs in again once on a 401 and retries the call")
  void reLogin() throws IOException {
    AtomicInteger logins = new AtomicInteger();
    AtomicInteger reads = new AtomicInteger();
    start(
        path -> {
          if (path.equals("/api/auth/login")) {
            return new Reply(200, "{\"token\":\"t" + logins.incrementAndGet() + "\"}");
          }
          return reads.incrementAndGet() == 1 ? new Reply(401, "{}") : new Reply(200, "{}");
        });
    client().get("/api/hierarchy");
    assertThat(calls).endsWith("POST /api/auth/login null", "GET /api/hierarchy Bearer t2");
  }

  @Test
  @DisplayName("a refused login is an AUTH error and is not retried")
  void authFailure() throws IOException {
    start(path -> new Reply(403, "{}"));
    assertThatThrownBy(() -> client().get("/api/hierarchy"))
        .isInstanceOfSatisfying(
            DmpException.class, e -> assertThat(e.kind()).isEqualTo(DmpException.Kind.AUTH));
    assertThat(calls).hasSize(1);
  }

  @Test
  @DisplayName("a failing endpoint is tried 3 times, then reported")
  void retries() throws IOException {
    start(
        path ->
            path.equals("/api/auth/login")
                ? new Reply(200, "{\"token\":\"t\"}")
                : new Reply(500, "{}"));
    assertThatThrownBy(() -> client().get("/api/hierarchy"))
        .isInstanceOfSatisfying(
            DmpException.class, e -> assertThat(e.kind()).isEqualTo(DmpException.Kind.API_ERROR));
    assertThat(calls.stream().filter(c -> c.startsWith("GET"))).hasSize(3);
  }

  @Test
  @DisplayName("BR-30: reads attendance of one day page by page, with check-ins and confirmations")
  void attendance() throws IOException {
    start(
        path -> {
          Reply auth = login(path);
          if (auth != null) {
            return auth;
          }
          return path.contains("page=0")
              ? new Reply(
                  200,
                  """
                  {"content":[
                    {"employeeId":"PNJP1","workDate":"2026-11-03","checkIn":"2026-11-03T07:45:10"},
                    {"employeeId":"PNJP1","workDate":"2026-11-03","checkInTime":"12:30"},
                    {"employeeCode":"PNJP2","checkIn":null,"isConfirmed":true},
                    {"employeeId":"PNJP3","checkIn":"03-11-2026 08:15:00","confirmStatus":"CONFIRMED"},
                    {"employeeId":"PNJP4","workDate":"2026-11-02","checkIn":"07:40"},
                    {"employeeId":"PNJP5","checkIn":null},
                    {"checkIn":"07:00"}
                  ],"number":0,"totalPages":2,"last":false}
                  """)
              : new Reply(
                  200,
                  "{\"content\":[{\"employeeId\":\"PNJP6\",\"checkIn\":\"2026-11-03T00:50:00Z\"}],"
                      + "\"number\":1,\"totalPages\":2}");
        });
    LocalDate day = LocalDate.of(2026, 11, 3);
    assertThat(client().getAttendance(day))
        .containsExactly(
            new DmpClient.AttendanceRecord(
                "PNJP1", VnTime.vnToUtc(day, LocalTime.of(7, 45, 10)), false),
            new DmpClient.AttendanceRecord(
                "PNJP1", VnTime.vnToUtc(day, LocalTime.of(12, 30)), false),
            new DmpClient.AttendanceRecord("PNJP2", null, true),
            new DmpClient.AttendanceRecord("PNJP3", VnTime.vnToUtc(day, LocalTime.of(8, 15)), true),
            new DmpClient.AttendanceRecord(
                "PNJP6", VnTime.vnToUtc(day, LocalTime.of(7, 50)), false));
    assertThat(calls.stream().filter(c -> c.startsWith("GET"))).hasSize(2);
    assertThat(queries)
        .anyMatch(
            q ->
                q.contains(
                    "fromDate=03-11-2026&toDate=03-11-2026&companyCode=PNJP&page=0&size=500"));
  }

  @Test
  @DisplayName("BR-35: reads leave flags with their day type; rows without leave are dropped")
  void leave() throws IOException {
    start(
        path -> {
          Reply auth = login(path);
          if (auth != null) {
            return auth;
          }
          return new Reply(
              200,
              """
              {"content":[
                {"employeeCode":"PNJP1","forecastDate":"2026-11-03","restStatus":"P",
                 "leaveTypeName":"Nghỉ phép năm","leaveDayType":"AM"},
                {"employeeCode":"PNJP2","forecastDate":"2026-11-04","leaveDayType":"PM"},
                {"employeeCode":"PNJP3","forecastDate":"2026-11-04","leaveTypeName":"Ốm"},
                {"employeeCode":"PNJP4","forecastDate":"2026-11-04"}
              ],"last":true}
              """);
        });
    assertThat(client().getLeave(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 17)))
        .containsExactly(
            new DmpClient.LeaveRecord(
                "PNJP1", LocalDate.of(2026, 11, 3), DmpClient.LeaveDayType.AM, "Nghỉ phép năm"),
            new DmpClient.LeaveRecord(
                "PNJP2", LocalDate.of(2026, 11, 4), DmpClient.LeaveDayType.PM, "Nghỉ phép"),
            new DmpClient.LeaveRecord(
                "PNJP3", LocalDate.of(2026, 11, 4), DmpClient.LeaveDayType.FULL, "Ốm"));
    assertThat(queries)
        .anyMatch(q -> q.contains("fromDate=2026-11-03&toDate=2026-11-17&hasLeave=true"));
  }

  @Test
  @DisplayName("an attendance response without rows fails the read")
  void attendanceNotRows() throws IOException {
    start(path -> login(path) != null ? login(path) : new Reply(200, "{\"total\":0}"));
    assertThatThrownBy(() -> client().getAttendance(LocalDate.of(2026, 11, 3)))
        .isInstanceOf(DmpException.class)
        .hasMessageContaining("did not return rows");
  }
}
