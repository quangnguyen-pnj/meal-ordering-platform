package vn.pnj.meal.api.attendance;

import static vn.pnj.meal.api.menu.MenuController.date;

import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.pnj.meal.api.access.RequestPrincipal;
import vn.pnj.meal.api.access.RequirePermission;
import vn.pnj.meal.api.access.ScopeType;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.access.Action;

/** Plan section 3: "Re-run attendance sync" for the HC admin (and the system admin). */
@RestController
public class AttendanceController {
  private final AttendanceService attendance;
  private final ClockService clock;

  public AttendanceController(AttendanceService attendance, ClockService clock) {
    this.attendance = attendance;
    this.clock = clock;
  }

  /**
   * @param date the serving day; today when absent
   */
  public record RerunBody(String date) {}

  /**
   * BR-34: attendance and leave from DMP now; a held cutoff of today is evaluated again. 502 when
   * DMP fails.
   */
  @RequirePermission(action = Action.SYNC_MANAGE, scope = ScopeType.FACTORY)
  @PostMapping("/admin/attendance/sync")
  AttendanceService.RerunResult rerun(@Valid @RequestBody RerunBody body, RequestPrincipal p) {
    LocalDate day = body.date() == null ? VnTime.vnDateOf(clock.now()) : date(body.date(), "date");
    return attendance.rerun(p.factoryId(), day, p.employeeId());
  }
}
