package vn.pnj.meal.api.jobs;

import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.pnj.meal.api.attendance.AttendanceService;
import vn.pnj.meal.api.close.DayCloseService;
import vn.pnj.meal.api.me.MeService;
import vn.pnj.meal.api.org.OrgSyncService;
import vn.pnj.meal.api.registrations.ForecastService;

/**
 * Background jobs (plan 6.5). Jobs only do proactive work; every deadline is still checked on each
 * request, so a missed run never breaks a rule. The handlers are plain methods so tests call them
 * directly; the schedules run only when {@link JobsConfig} is active. Cron times are Vietnam time.
 */
@Component
public class Jobs {
  private static final Logger log = LoggerFactory.getLogger(Jobs.class);
  private static final String TZ = "Asia/Ho_Chi_Minh";

  private final JdbcClient jdbc;
  private final OrgSyncService orgSync;
  private final MeService me;
  private final ForecastService forecast;
  private final AttendanceService attendance;
  private final DayCloseService dayClose;

  public Jobs(
      JdbcClient jdbc,
      OrgSyncService orgSync,
      MeService me,
      ForecastService forecast,
      AttendanceService attendance,
      DayCloseService dayClose) {
    this.jdbc = jdbc;
    this.orgSync = orgSync;
    this.me = me;
    this.forecast = forecast;
    this.attendance = attendance;
    this.dayClose = dayClose;
  }

  private List<String> factories() {
    return jdbc.sql("SELECT id FROM \"Factory\" ORDER BY id").query(String.class).list();
  }

  /** Daily at 02:00: org tree and employees from DMP (BR-60..BR-63). Idempotent. */
  @Scheduled(cron = "0 0 2 * * *", zone = TZ)
  @SchedulerLock(name = "sync-org-employees", lockAtMostFor = "PT30M")
  public void syncOrgEmployees() {
    for (String factoryId : factories()) {
      OrgSyncService.Result result = orgSync.run(factoryId, null);
      log.info("sync-org-employees {}: {}", factoryId, result.status());
    }
  }

  /**
   * BR-06: from {@code menu.publishWarnAt} (Monday 12:00), hourly until published. It runs every
   * hour and checks the setting, so moving the warning time needs no new schedule. The banner is
   * computed per request; the job leaves a trace in the logs.
   */
  @Scheduled(cron = "0 0 * * * *", zone = TZ)
  @SchedulerLock(name = "menu-publish-warning", lockAtMostFor = "PT5M")
  public void menuPublishWarningJob() {
    menuPublishWarning();
  }

  public int menuPublishWarning() {
    int count = 0;
    for (String factoryId : factories()) {
      for (MeService.BannerView warning : me.menuWarnings(factoryId)) {
        count++;
        log.warn("Menu for {} is not published yet", warning.params());
      }
    }
    return count;
  }

  /**
   * BR-07: the forecast snapshot at each week's closeAt. closeAt differs per week (BR-04), so the
   * job runs every minute and snapshots whatever is due; requests also catch up (plan 6.5).
   */
  @Scheduled(cron = "0 * * * * *", zone = TZ)
  @SchedulerLock(name = "close-week", lockAtMostFor = "PT5M", lockAtLeastFor = "PT20S")
  public void closeWeekJob() {
    closeWeek();
  }

  public int closeWeek() {
    int closed = forecast.closeDueWeeks(null);
    if (closed > 0) {
      log.info("close-week: {} forecast snapshot(s) taken", closed);
    }
    return closed;
  }

  /** BR-14: registrations still waiting for reselection are cancelled at the day's lock. */
  @Scheduled(cron = "30 * * * * *", zone = TZ)
  @SchedulerLock(name = "reselect-deadline", lockAtMostFor = "PT5M", lockAtLeastFor = "PT20S")
  public void reselectDeadlineJob() {
    reselectDeadline();
  }

  public int reselectDeadline() {
    int cancelled = forecast.cancelUnselectedAtLock(null);
    if (cancelled > 0) {
      log.info("reselect-deadline: {} registration(s) cancelled", cancelled);
    }
    return cancelled;
  }

  /**
   * BR-30 / BR-31: poll-attendance and provisional-cancel. The start time, the interval and the
   * cutoff are settings, so the job runs every minute and does whatever is due; serving days only.
   */
  @Scheduled(cron = "15 * * * * *", zone = TZ)
  @SchedulerLock(name = "poll-attendance", lockAtMostFor = "PT5M", lockAtLeastFor = "PT20S")
  public void pollAttendanceJob() {
    pollAttendance();
  }

  public void pollAttendance() {
    for (String factoryId : factories()) {
      attendance.tick(factoryId);
    }
  }

  /** BR-35: leave flags from today to 14 days ahead, at minute 30 of every hour. */
  @Scheduled(cron = "0 30 * * * *", zone = TZ)
  @SchedulerLock(name = "sync-leave", lockAtMostFor = "PT10M")
  public void syncLeave() {
    for (String factoryId : factories()) {
      AttendanceService.LeaveResult result = attendance.syncLeave(factoryId, null);
      log.info("sync-leave {}: {} ({} flags)", factoryId, result.status(), result.flags());
    }
  }

  /**
   * BR-43: close-reminder and auto-finalization. From {@code close.reminderAt} (08:45) the HC admin
   * is reminded while the day is open (the banner is computed per request; the job leaves a trace
   * in the logs); a serving day not finalized by {@code close.autoFinalizeAt} (12:00) is finalized
   * by the system (product owner, 2026-11-10). Both times are settings, so the job runs every
   * minute.
   */
  @Scheduled(cron = "45 * * * * *", zone = TZ)
  @SchedulerLock(name = "close-day", lockAtMostFor = "PT5M", lockAtLeastFor = "PT20S")
  public void closeDayJob() {
    closeDay();
  }

  public int closeDay() {
    int finalized = 0;
    for (String factoryId : factories()) {
      finalized += dayClose.finalizeDue(factoryId).size();
      if (dayClose.reminderDue(factoryId)) {
        log.warn("close-reminder {}: today is not finalized yet", factoryId);
      }
    }
    return finalized;
  }
}
