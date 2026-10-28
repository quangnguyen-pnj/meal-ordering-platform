package vn.pnj.meal.api.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.pnj.meal.api.support.IntegrationTest;

/**
 * Flyway builds the same schema the legacy Prisma migrations built (V1..V3 are copies), plus the
 * ShedLock table of the Java jobs (V4).
 */
class MigrationTest extends IntegrationTest {
  @Autowired JdbcClient jdbc;

  @Test
  void appliesTheBaselineMigrationsAndShedLock() {
    List<String> versions =
        jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
            .query(String.class)
            .list();
    assertThat(versions).containsExactly("1", "2", "3", "4");
  }

  @Test
  void createsEveryTableOfTheLegacySchema() {
    List<String> tables =
        jdbc.sql(
                "SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = current_schema() AND table_name <> 'flyway_schema_history'")
            .query(String.class)
            .list();
    assertThat(tables)
        .containsExactlyInAnyOrder(
            "Factory",
            "Setting",
            "Supplier",
            "Canteen",
            "Floor",
            "CostCenter",
            "OrgUnit",
            "Employee",
            "RoleAssignment",
            "AuthSession",
            "AuditLog",
            "SyncRun",
            "Dish",
            "Dessert",
            "MenuWeek",
            "MenuDay",
            "MenuItem",
            "MenuDayDessert",
            "Registration",
            "ForecastLine",
            "Notification",
            "MappingImport",
            "DishType",
            "shedlock");
  }

  @Test
  void keepsTheWriteOnceTriggerOnForecastLine() {
    assertThat(
            jdbc.sql("SELECT count(*) FROM pg_trigger WHERE tgname = 'ForecastLine_write_once'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void columnDefaultsStoreUtcWhateverTheJvmZone() {
    // The tests run in Asia/Ho_Chi_Minh; a session in that zone would put "createdAt" 7 h ahead.
    assertThat(jdbc.sql("SELECT current_setting('TimeZone')").query(String.class).single())
        .isEqualTo("UTC");
    LocalDateTime dbNow =
        jdbc.sql("SELECT CURRENT_TIMESTAMP::timestamp(3)").query(LocalDateTime.class).single();
    assertThat(Duration.between(dbNow.toInstant(ZoneOffset.UTC), Instant.now()).abs())
        .isLessThan(Duration.ofMinutes(1));
  }
}
