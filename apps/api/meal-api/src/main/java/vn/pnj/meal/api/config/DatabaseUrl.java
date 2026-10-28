package vn.pnj.meal.api.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the {@code DATABASE_URL} of {@code .env} ({@code postgresql://user:pass@host:5432/db
 * ?schema=public}, the form the legacy API and Docker Compose use) into JDBC settings, so one
 * variable serves every component.
 */
public record DatabaseUrl(String jdbcUrl, String username, String password) {

  public static DatabaseUrl parse(String databaseUrl) {
    URI uri;
    try {
      uri = new URI(databaseUrl.trim());
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("DATABASE_URL is not a valid URL", e);
    }
    String scheme = uri.getScheme();
    if (!"postgresql".equals(scheme) && !"postgres".equals(scheme)) {
      throw new IllegalArgumentException("DATABASE_URL must start with postgresql://");
    }
    if (uri.getHost() == null || uri.getRawPath() == null || uri.getRawPath().length() < 2) {
      throw new IllegalArgumentException("DATABASE_URL must name a host and a database");
    }

    String username = null;
    String password = null;
    if (uri.getRawUserInfo() != null) {
      String[] parts = uri.getRawUserInfo().split(":", 2);
      username = decode(parts[0]);
      password = parts.length > 1 ? decode(parts[1]) : null;
    }

    List<String> params = new ArrayList<>();
    if (uri.getRawQuery() != null) {
      for (String param : uri.getRawQuery().split("&")) {
        if (param.isEmpty()) {
          continue;
        }
        // Prisma's ?schema= is PostgreSQL JDBC's ?currentSchema=.
        params.add(param.startsWith("schema=") ? "currentSchema=" + param.substring(7) : param);
      }
    }

    StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
    if (uri.getPort() != -1) {
      jdbc.append(':').append(uri.getPort());
    }
    jdbc.append(uri.getRawPath());
    if (!params.isEmpty()) {
      jdbc.append('?').append(String.join("&", params));
    }
    return new DatabaseUrl(jdbc.toString(), username, password);
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  @Override
  public String toString() {
    return "DatabaseUrl[" + jdbcUrl + ", user=" + username + "]";
  }
}
