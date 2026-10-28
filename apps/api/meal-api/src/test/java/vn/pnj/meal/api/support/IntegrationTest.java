package vn.pnj.meal.api.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.pnj.meal.api.auth.PinHasher;
import vn.pnj.meal.api.auth.SessionService;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.seed.SeedService;
import vn.pnj.meal.rules.VnTime;

/**
 * Base for tests that run the whole API against a real PostgreSQL with migrations applied and the
 * development seed loaded once per test JVM (1 factory, the 6-level tree, 1,700 employees).
 */
@SpringBootTest(
    properties = {
      "meal.environment=test",
      "meal.session-secret=test-secret-test-secret-test-secret-1234",
      "meal.clock-fake-enabled=true",
      "meal.jobs-enabled=false",
      "meal.first-system-admin-code=",
      "springdoc.api-docs.enabled=true",
      "spring.config.import="
    })
@AutoConfigureMockMvc
public abstract class IntegrationTest {
  public static final String INITIAL_PIN = "123456";
  private static boolean seeded;
  private static final Map<String, String> HASHES = new HashMap<>();

  @Autowired protected MockMvc mvc;
  @Autowired protected JdbcClient jdbc;
  @Autowired protected ClockService clock;
  @Autowired private PinHasher hasher;
  @Autowired private SeedService seed;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> TestDatabase.url().jdbcUrl());
    registry.add("spring.datasource.username", () -> TestDatabase.url().username());
    registry.add("spring.datasource.password", () -> TestDatabase.url().password());
  }

  @BeforeEach
  void seedOnce() {
    synchronized (IntegrationTest.class) {
      if (!seeded) {
        seed.run();
        seeded = true;
      }
    }
  }

  @AfterEach
  void realTime() {
    clock.clearFakeTime();
  }

  /** Puts an employee back into a known state: the given PIN, no lock, signed out. */
  protected void resetEmployee(String employeeCode, String pin, boolean mustChange) {
    String hash;
    synchronized (HASHES) {
      hash = HASHES.computeIfAbsent(pin, hasher::hash);
    }
    jdbc.sql(
            """
            UPDATE "Employee" SET "pinHash" = :hash, "pinMustChange" = :mustChange,
                   "pinFailedCount" = 0, "lockedUntil" = NULL
            WHERE "employeeCode" = :code
            """)
        .param("hash", hash)
        .param("mustChange", mustChange)
        .param("code", employeeCode)
        .update();
    jdbc.sql(
            """
            DELETE FROM "AuthSession"
            WHERE "employeeId" = (SELECT id FROM "Employee" WHERE "employeeCode" = :code)
            """)
        .param("code", employeeCode)
        .update();
  }

  protected void resetEmployee(String employeeCode) {
    resetEmployee(employeeCode, INITIAL_PIN, true);
  }

  /** Signs in with employee code + PIN and returns the session cookie. */
  protected Cookie signIn(String employeeCode, String pin) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/auth/pin/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"employeeCode\":\"%s\",\"pin\":\"%s\"}".formatted(employeeCode, pin)))
            .andExpect(status().isOk())
            .andReturn();
    return result.getResponse().getCookie(SessionService.COOKIE);
  }

  protected Cookie signIn(String employeeCode) throws Exception {
    return signIn(employeeCode, INITIAL_PIN);
  }

  /** A known, non-temporary PIN, so other features are not blocked by the forced PIN change. */
  protected Cookie signInReady(String employeeCode) throws Exception {
    resetEmployee(employeeCode, INITIAL_PIN, false);
    return signIn(employeeCode);
  }

  /** Freezes the clock at a Vietnam wall time. Sessions age with fake time too. */
  protected void setVnTime(String date, String time) {
    clock.setFakeTime(VnTime.vnToUtc(LocalDate.parse(date), LocalTime.parse(time)));
  }

  protected String employeeId(String employeeCode) {
    return jdbc.sql("SELECT id FROM \"Employee\" WHERE \"employeeCode\" = :c")
        .param("c", employeeCode)
        .query(String.class)
        .single();
  }

  protected static String json(String singleQuoted) {
    return singleQuoted.replace('\'', '"');
  }

  /** Sends a request as {@code who} (null: no session) with an optional JSON body. */
  protected MockHttpServletResponse send(Cookie who, HttpMethod method, String url, String body)
      throws Exception {
    MockHttpServletRequestBuilder request = request(method, url);
    if (who != null) {
      request.cookie(who);
    }
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
    response.setCharacterEncoding("UTF-8");
    return response;
  }

  /** GET that must answer 200; returns the body. */
  protected String read(Cookie who, String url) throws Exception {
    MockHttpServletResponse res = send(who, HttpMethod.GET, url, null);
    assertThat(res.getStatus()).as("GET " + url).isEqualTo(200);
    return res.getContentAsString();
  }

  /** Sends and checks the status; returns the body. */
  protected String expect(int status, Cookie who, HttpMethod method, String url, String body)
      throws Exception {
    MockHttpServletResponse res = send(who, method, url, body == null ? null : json(body));
    assertThat(res.getStatus())
        .as(method + " " + url + " -> " + res.getContentAsString())
        .isEqualTo(status);
    return res.getContentAsString();
  }

  /** An endpoint for the "403 on every admin endpoint" checks (plan 7.1 rule 5). */
  protected record Endpoint(HttpMethod method, String url, String body) {
    public static Endpoint get(String url) {
      return new Endpoint(HttpMethod.GET, url, null);
    }

    public static Endpoint of(HttpMethod method, String url, String singleQuotedBody) {
      return new Endpoint(method, url, singleQuotedBody);
    }
  }

  /** Plan 7.1 rule 5: every admin endpoint has a 403 test. Asserts 403 for each caller. */
  protected void expectForbidden(List<Cookie> callers, List<Endpoint> endpoints) throws Exception {
    List<String> failures = new ArrayList<>();
    for (Cookie who : callers) {
      for (Endpoint e : endpoints) {
        int status =
            send(who, e.method(), e.url(), e.body() == null ? null : json(e.body())).getStatus();
        if (status != 403) {
          failures.add(e.method() + " " + e.url() + " -> " + status);
        }
      }
    }
    assertThat(failures).isEmpty();
  }
}
