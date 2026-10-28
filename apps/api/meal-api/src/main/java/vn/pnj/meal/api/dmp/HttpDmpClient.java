package vn.pnj.meal.api.dmp;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import vn.pnj.meal.api.config.AppProperties;

/**
 * The real DMP client (plan section 5): service-token login and a GET helper with the 10 s timeout
 * and 3 attempts with backoff, re-logging in once on 401. Org tree and employees are implemented;
 * attendance and leave follow in P3. Never used against a real DMP in tests.
 *
 * <p>Endpoint notes: GET /api/hr-employees (page, size <= 500, sort=employeeId,asc; Spring Page,
 * sequential pages); GET /api/hierarchy (full tree, unpaged); GET /api/attendance (fromDate, toDate
 * as dd-MM-yyyy, companyCode; earliest check-in per person); GET /api/daily-forecast
 * (hasLeave=true, ISO dates; flags only, BR-35). Never: /api/employee-view,
 * /api/hr-employees/search/medic, generate-data, dateAttendanceForecast.
 */
public class HttpDmpClient implements DmpClient {
  static final Duration TIMEOUT = Duration.ofSeconds(10);
  static final int MAX_ATTEMPTS = 3;
  static final int PAGE_SIZE = 500;

  /** Safety stop: 1,700 employees fit in 4 pages; 200 pages means the paging is broken. */
  static final int MAX_PAGES = 200;

  private final RestClient http;
  private final AppProperties.Dmp config;
  private volatile String token;

  public HttpDmpClient(AppProperties.Dmp config) {
    this(config, RestClient.builder());
  }

  HttpDmpClient(AppProperties.Dmp config, RestClient.Builder builder) {
    this.config = config;
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    factory.setReadTimeout(TIMEOUT);
    this.http =
        builder
            .baseUrl(config.baseUrl() == null ? "" : config.baseUrl().replaceAll("/+$", ""))
            .requestFactory(factory)
            .build();
  }

  /** GET /api/hierarchy: the full tree, unpaged. */
  @Override
  public List<OrgNode> getOrgTree() {
    return DmpParsers.parseHierarchy(get("/api/hierarchy"));
  }

  /** GET /api/hr-employees: pages fetched one after another, size 500, sorted by employeeId. */
  @Override
  public List<Employee> getEmployees() {
    List<Employee> all = new ArrayList<>();
    for (int page = 0; page < MAX_PAGES; page++) {
      DmpParsers.EmployeePage result =
          DmpParsers.parseEmployeePage(
              get(
                  "/api/hr-employees?page="
                      + page
                      + "&size="
                      + PAGE_SIZE
                      + "&sort=employeeId,asc"));
      all.addAll(result.items());
      if (result.last()) {
        return all;
      }
    }
    throw new DmpException(
        DmpException.Kind.API_ERROR,
        "DMP /api/hr-employees returned more than " + MAX_PAGES + " pages");
  }

  @Override
  public List<AttendanceRecord> getAttendance(LocalDate date) {
    throw notImplemented("getAttendance", "P3");
  }

  @Override
  public List<LeaveRecord> getLeave(LocalDate fromDate, LocalDate toDate) {
    throw notImplemented("getLeave", "P3");
  }

  /** POST /api/auth/login with the LDAP service account; the JWT has no refresh token. */
  String login() {
    JsonNode body;
    try {
      body =
          http.post()
              .uri("/api/auth/login")
              .contentType(MediaType.APPLICATION_JSON)
              .body(Map.of("email", config.serviceEmail(), "password", config.servicePassword()))
              .retrieve()
              .onStatus(
                  HttpStatusCode::isError,
                  (req, res) -> {
                    throw new DmpException(
                        DmpException.Kind.AUTH,
                        "DMP login failed with status " + res.getStatusCode().value());
                  })
              .body(JsonNode.class);
    } catch (ResourceAccessException e) {
      throw transport("/api/auth/login", e);
    }
    // TODO(BR-61): confirm the token field name against DMP /v3/api-docs (plan section 5).
    for (String field : List.of("token", "accessToken", "jwt")) {
      if (body != null && body.hasNonNull(field)) {
        token = body.get(field).asString();
        return token;
      }
    }
    throw new DmpException(DmpException.Kind.AUTH, "DMP login response held no token");
  }

  /** GET with the bearer token; re-logs in once on 401, retries other failures with backoff. */
  JsonNode get(String path) {
    DmpException last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        String current = token != null ? token : login();
        try {
          return getOnce(path, current);
        } catch (RestClientResponseException e) {
          if (e.getStatusCode().value() != 401) {
            throw e;
          }
          return getOnce(path, login());
        }
      } catch (DmpException e) {
        if (e.kind() == DmpException.Kind.AUTH) {
          throw e;
        }
        last = e;
      } catch (RestClientResponseException e) {
        last =
            new DmpException(
                DmpException.Kind.API_ERROR,
                "DMP " + path + " failed with status " + e.getStatusCode().value());
      } catch (ResourceAccessException e) {
        last = transport(path, e);
      }
      if (attempt < MAX_ATTEMPTS) {
        sleep(500L << (attempt - 1));
      }
    }
    throw last;
  }

  private JsonNode getOnce(String path, String bearer) {
    return http.get()
        .uri(path)
        .headers(h -> h.setBearerAuth(bearer))
        .retrieve()
        .body(JsonNode.class);
  }

  private static DmpException transport(String path, ResourceAccessException e) {
    if (e.getCause() instanceof HttpTimeoutException) {
      return new DmpException(
          DmpException.Kind.TIMEOUT,
          "DMP " + path + " timed out after " + TIMEOUT.toSeconds() + " s",
          e);
    }
    return new DmpException(DmpException.Kind.API_ERROR, "DMP " + path + ": " + e.getMessage(), e);
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DmpException(DmpException.Kind.API_ERROR, "Interrupted while retrying DMP");
    }
  }

  private static DmpException notImplemented(String method, String phase) {
    return new DmpException(
        DmpException.Kind.NOT_IMPLEMENTED,
        "HttpDmpClient." + method + " is implemented in " + phase);
  }
}
