package vn.pnj.meal.api.jobs;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns the schedules of {@link Jobs} on when {@code JOBS_ENABLED} (off in tests and, until J3, in
 * the Compose {@code api-java} service, where the legacy API still runs them). ShedLock keeps each
 * job to one instance at a time, through the {@code shedlock} table (Flyway V4).
 */
@Configuration
@ConditionalOnProperty(name = "meal.jobs-enabled", havingValue = "true")
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class JobsConfig {

  @Bean
  LockProvider lockProvider(DataSource dataSource) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(dataSource))
            // The database clock decides who holds a lock, so app servers may drift apart.
            .usingDbTime()
            .build());
  }
}
