package vn.pnj.meal.api.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import vn.pnj.meal.api.config.DatabaseUrl;

/**
 * One throwaway PostgreSQL per test JVM, started on first use (embedded binaries, no Docker). Set
 * TEST_DATABASE_URL (same form as DATABASE_URL) to use an existing, empty, disposable database
 * instead.
 */
public final class TestDatabase {
  private static final String DB_NAME = "meal_test";
  private static DatabaseUrl url;

  private TestDatabase() {}

  public static synchronized DatabaseUrl url() {
    if (url == null) {
      String external = System.getenv("TEST_DATABASE_URL");
      url = external != null && !external.isBlank() ? DatabaseUrl.parse(external) : startEmbedded();
    }
    return url;
  }

  private static DatabaseUrl startEmbedded() {
    try {
      EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
      Runtime.getRuntime().addShutdownHook(new Thread(() -> closeQuietly(postgres)));
      try (Connection connection = postgres.getPostgresDatabase().getConnection();
          Statement statement = connection.createStatement()) {
        // UTF8 explicitly: Vietnamese text must survive whatever the OS locale is.
        statement.execute(
            "CREATE DATABASE "
                + DB_NAME
                + " ENCODING 'UTF8' TEMPLATE template0"
                + " LC_COLLATE 'C' LC_CTYPE 'C'");
      }
      return new DatabaseUrl(postgres.getJdbcUrl("postgres", DB_NAME), "postgres", "postgres");
    } catch (IOException | SQLException e) {
      throw new IllegalStateException("Could not start the embedded PostgreSQL", e);
    }
  }

  private static void closeQuietly(EmbeddedPostgres postgres) {
    try {
      postgres.close();
    } catch (IOException ignored) {
      // The JVM is exiting; the data directory is temporary anyway.
    }
  }
}
