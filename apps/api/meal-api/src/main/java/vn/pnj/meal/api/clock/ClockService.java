package vn.pnj.meal.api.clock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.config.AppProperties;

/**
 * The only place in the API that reads the wall clock (plan 7.1 rule 3, enforced by {@code
 * ArchitectureTest}). Business code calls {@link #now()} and passes the result into rule functions.
 *
 * <p>Fake-time mode freezes {@code now()} at a chosen instant so tests and demos can walk through
 * deadlines (e.g. Thursday 23:59:30 vs Friday 00:00). It needs CLOCK_FAKE_ENABLED and is refused in
 * production, both here and in {@link AppProperties}.
 */
@Service
public class ClockService {
  private final Clock system;
  private final boolean fakeTimeAllowed;
  private volatile Instant fakeNow;

  @Autowired
  public ClockService(AppProperties properties) {
    this(Clock.systemUTC(), properties.fakeTimeAllowed());
  }

  ClockService(Clock system, boolean fakeTimeAllowed) {
    this.system = system;
    this.fakeTimeAllowed = fakeTimeAllowed;
  }

  public boolean fakeTimeAllowed() {
    return fakeTimeAllowed;
  }

  /**
   * Current instant: the frozen fake time when set, otherwise the real time. Milliseconds, like the
   * {@code TIMESTAMP(3)} columns, so a stored instant compares equal to the one it came from.
   */
  public Instant now() {
    Instant fake = fakeNow;
    return fake != null ? fake : system.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  public boolean isFake() {
    return fakeNow != null;
  }

  public void setFakeTime(Instant at) {
    assertAllowed();
    fakeNow = at.truncatedTo(ChronoUnit.MILLIS);
  }

  public synchronized void advanceFakeTime(Duration by) {
    assertAllowed();
    if (fakeNow == null) {
      throw new IllegalStateException("Fake time is not set");
    }
    fakeNow = fakeNow.plus(by);
  }

  public void clearFakeTime() {
    fakeNow = null;
  }

  private void assertAllowed() {
    if (!fakeTimeAllowed) {
      throw ApiException.forbidden("Fake time is disabled in this environment");
    }
  }
}
