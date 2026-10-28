package vn.pnj.meal.api.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Timestamps are {@code TIMESTAMP(3)} without time zone holding UTC (plan 7.1 rule 4). JDBC would
 * read and write {@code java.sql.Timestamp} in the JVM's zone (Asia/Ho_Chi_Minh in Docker), so
 * {@code JdbcClient} code converts through these instead. Hibernate does the same through {@code
 * hibernate.jdbc.time_zone=UTC}.
 */
public final class SqlTime {
  private SqlTime() {}

  /** A query parameter for a timestamp column. */
  public static LocalDateTime utc(Instant instant) {
    return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  public static Instant instant(ResultSet rs, String column) throws SQLException {
    LocalDateTime value = rs.getObject(column, LocalDateTime.class);
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }
}
