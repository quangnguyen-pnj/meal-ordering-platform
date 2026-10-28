package vn.pnj.meal.api.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.pnj.meal.api.support.IntegrationTest;

/** The jobs' lock provider works on the Flyway V4 table: one holder at a time. */
class ShedLockTest extends IntegrationTest {
  @Autowired DataSource dataSource;

  @Test
  @DisplayName("a job lock is held by one caller until it is released")
  void oneHolder() {
    LockProvider provider = new JobsConfig().lockProvider(dataSource);
    LockConfiguration config =
        new LockConfiguration(Instant.now(), "shedlock-test", Duration.ofMinutes(1), Duration.ZERO);
    Optional<SimpleLock> first = provider.lock(config);
    assertThat(first).isPresent();
    assertThat(provider.lock(config)).isEmpty();
    first.get().unlock();
    Optional<SimpleLock> again = provider.lock(config);
    assertThat(again).isPresent();
    again.get().unlock();
  }
}
