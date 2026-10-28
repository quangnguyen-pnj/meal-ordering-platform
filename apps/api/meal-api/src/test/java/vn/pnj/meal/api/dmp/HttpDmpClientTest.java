package vn.pnj.meal.api.dmp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.api.config.AppProperties;

/** HttpDmpClient against a local fake DMP; never the real one (plan 7.1 rule 8). */
@DisplayName("HttpDmpClient")
class HttpDmpClientTest {
  private HttpServer server;
  private final List<String> calls = new CopyOnWriteArrayList<>();

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
          Reply reply = handler.handle(path);
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

  @Test
  @DisplayName("leaves the endpoint methods to their phases")
  void notImplemented() throws IOException {
    start(path -> new Reply(200, "{}"));
    assertThatThrownBy(() -> client().getAttendance(LocalDate.of(2026, 10, 19)))
        .isInstanceOf(DmpException.class)
        .hasMessageContaining("P3");
    assertThatThrownBy(() -> client().getOrgTree()).hasMessageContaining("J2");
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
}
