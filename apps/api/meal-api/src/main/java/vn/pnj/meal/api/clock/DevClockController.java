package vn.pnj.meal.api.clock;

import jakarta.validation.Valid;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.pnj.meal.api.access.RequestPrincipal;
import vn.pnj.meal.api.access.RequirePermission;
import vn.pnj.meal.api.access.ScopeType;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.common.Times;
import vn.pnj.meal.rules.access.Action;

/**
 * Fake-time controls for tests and demos. Only registered when fake time is allowed (never in
 * production); system admins only; every change is audited.
 */
@RestController
@RequestMapping("/dev/clock")
@Conditional(FakeTimeAllowedCondition.class)
public class DevClockController {
  private final ClockService clock;
  private final AuditService audit;

  public DevClockController(ClockService clock, AuditService audit) {
    this.clock = clock;
    this.audit = audit;
  }

  public record ClockView(boolean fake, String now) {}

  /**
   * @param at an ISO instant with offset, e.g. "2026-10-22T23:59:30+07:00"; null returns to real
   *     time
   */
  public record SetClockBody(String at) {}

  @RequirePermission(action = Action.INTEGRATION_CONFIGURE, scope = ScopeType.SYSTEM)
  @GetMapping
  ClockView get() {
    return new ClockView(clock.isFake(), Times.iso(clock.now()));
  }

  @RequirePermission(action = Action.INTEGRATION_CONFIGURE, scope = ScopeType.SYSTEM)
  @PutMapping
  ClockView set(@Valid @RequestBody SetClockBody body, RequestPrincipal principal) {
    Instant at = null;
    if (body.at() != null) {
      try {
        at = OffsetDateTime.parse(body.at()).toInstant();
      } catch (RuntimeException e) {
        throw ApiException.invalid(
            "Validation failed",
            java.util.List.of(Map.of("path", "at", "message", "Expected an ISO date-time")));
      }
    }
    String before = clock.isFake() ? Times.iso(clock.now()) : null;
    if (at == null) {
      clock.clearFakeTime();
    } else {
      clock.setFakeTime(at);
    }
    Map<String, Object> beforeJson = new HashMap<>();
    beforeJson.put("fakeNow", before);
    Map<String, Object> afterJson = new HashMap<>();
    afterJson.put("fakeNow", body.at());
    audit.log(
        AuditService.Entry.of(principal.employeeId(), "dev.clock.set", "Clock", "fake-time")
            .withBefore(beforeJson)
            .withAfter(afterJson));
    return get();
  }
}
