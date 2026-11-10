package vn.pnj.meal.api.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.close.DayCloseStore;
import vn.pnj.meal.api.common.ApiErrorCode;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.db.SqlTime;
import vn.pnj.meal.api.dmp.DmpClient;
import vn.pnj.meal.api.settings.SettingKey;
import vn.pnj.meal.api.settings.SettingsService;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.attendance.AttendanceRules;
import vn.pnj.meal.rules.attendance.AttendanceRules.CutoffOutcome;
import vn.pnj.meal.rules.attendance.AttendanceRules.PresenceState;
import vn.pnj.meal.rules.close.DayCloseRules;

/**
 * Attendance on day D (BR-30 to BR-35): the attendance sync, the 08:10 cutoff run with its safety
 * check, leave flags and an admin's presence confirmation. Registrations are never changed here:
 * {@code DailyPresence} decides whether an employee's meals count (plan 6.3).
 *
 * <p>Like the org sync, every DMP read writes a {@code SyncRun} and data is written only after the
 * whole read succeeded (plan section 5). Automatic cancellations and restores are audited with no
 * actor (BR-70).
 *
 * <p>After finalization (BR-43) attendance is only recorded until {@code close.adjustmentDeadline}:
 * nothing is restored, and what arrives for a cancelled employee is a case to review (BR-32, plan
 * v1.4).
 */
@Service
public class AttendanceService {
  private static final Logger log = LoggerFactory.getLogger(AttendanceService.class);

  /** BR-35 / plan 6.5: leave flags from today to 14 days ahead. */
  static final int LEAVE_DAYS_AHEAD = 14;

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final JsonMapper json;
  private final ClockService clock;
  private final AuditService audit;
  private final SettingsService settings;
  private final DmpClient dmp;
  private final DayCloseStore days;

  public AttendanceService(
      JdbcClient jdbc,
      TransactionTemplate tx,
      JsonMapper json,
      ClockService clock,
      AuditService audit,
      SettingsService settings,
      DmpClient dmp,
      DayCloseStore days) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.json = json;
    this.clock = clock;
    this.audit = audit;
    this.settings = settings;
    this.dmp = dmp;
    this.days = days;
  }

  // --- reads -------------------------------------------------------------------------------------

  /**
   * One employee's presence on day D.
   *
   * @param confirmReason BR-33: optional; never shown to colleagues (BR-27)
   */
  public record Presence(
      PresenceState state,
      @Nullable Instant firstCheckInAt,
      @Nullable Instant dmpConfirmedAt,
      @Nullable Instant provisionalAt,
      @Nullable String confirmedByName,
      @Nullable Instant confirmedAt,
      @Nullable String confirmReason,
      @Nullable Instant lateCheckInAt) {

    public static final Presence NONE =
        new Presence(PresenceState.UNKNOWN, null, null, null, null, null, null, null);

    public boolean confirmedByAdmin() {
      return confirmedAt != null;
    }
  }

  /** Presence of the given employees on day D; employees without a row are absent from the map. */
  public Map<String, Presence> presenceOf(List<String> employeeIds, LocalDate date) {
    Map<String, Presence> out = new HashMap<>();
    if (employeeIds.isEmpty()) {
      return out;
    }
    jdbc.sql(
            """
            SELECT p."employeeId", p.state::text, p."firstCheckInAt", p."dmpConfirmedAt",
                   p."provisionalAt", c.name AS "confirmedByName", p."confirmedAt",
                   p."confirmReason", p."lateCheckInAt"
            FROM "DailyPresence" p LEFT JOIN "Employee" c ON c.id = p."confirmedById"
            WHERE p.date = :date AND p."employeeId" IN (:ids)
            """)
        .param("date", date)
        .param("ids", employeeIds)
        .query(
            (rs, i) ->
                out.put(
                    rs.getString("employeeId"),
                    new Presence(
                        PresenceState.valueOf(rs.getString("state")),
                        SqlTime.instant(rs, "firstCheckInAt"),
                        SqlTime.instant(rs, "dmpConfirmedAt"),
                        SqlTime.instant(rs, "provisionalAt"),
                        rs.getString("confirmedByName"),
                        SqlTime.instant(rs, "confirmedAt"),
                        rs.getString("confirmReason"),
                        SqlTime.instant(rs, "lateCheckInAt"))))
        .list();
    return out;
  }

  /** BR-35: a leave flag, shown only (FULL, AM or PM). */
  public record LeaveFlag(
      @io.swagger.v3.oas.annotations.media.Schema(allowableValues = {"FULL", "AM", "PM"})
          String dayType,
      String leaveTypeName) {}

  public Map<String, LeaveFlag> leaveOf(List<String> employeeIds, LocalDate date) {
    Map<String, LeaveFlag> out = new HashMap<>();
    if (employeeIds.isEmpty()) {
      return out;
    }
    jdbc.sql(
            """
            SELECT "employeeId", "dayType"::text, "leaveTypeName" FROM "LeaveFlag"
            WHERE date = :date AND "removedAt" IS NULL AND "employeeId" IN (:ids)
            """)
        .param("date", date)
        .param("ids", employeeIds)
        .query(
            (rs, i) ->
                out.put(
                    rs.getString("employeeId"),
                    new LeaveFlag(rs.getString("dayType"), rs.getString("leaveTypeName"))))
        .list();
    return out;
  }

  /** A serving day: some canteen of the factory has a published menu serving that day. */
  public boolean isServingDay(String factoryId, LocalDate date) {
    return jdbc.sql(
            """
            SELECT EXISTS (
              SELECT 1 FROM "MenuDay" d
              JOIN "MenuWeek" w ON w.id = d."menuWeekId"
              WHERE w."factoryId" = :factoryId AND w.status <> 'DRAFT' AND d.date = :date
                AND d."isServing")
            """)
        .param("factoryId", factoryId)
        .param("date", date)
        .query(Boolean.class)
        .single();
  }

  public LocalTime cutoffTime(String factoryId) {
    return settings.getTime(factoryId, SettingKey.ATTENDANCE_CUTOFF);
  }

  /**
   * @param error the DMP error of a failed run, for the HC admin (never a secret: DMP errors carry
   *     a status or a timeout, not credentials)
   */
  public record SyncRunSummary(
      String id,
      @io.swagger.v3.oas.annotations.media.Schema(
              allowableValues = {"RUNNING", "SUCCESS", "FAILED"})
          String status,
      Instant startedAt,
      @Nullable Instant finishedAt,
      @Nullable Integer recordCount,
      @Nullable String error) {}

  public record CutoffSummary(
      Instant runAt,
      @io.swagger.v3.oas.annotations.media.Schema(
              allowableValues = {"CANCELLED", "HELD_SYNC_FAILED", "HELD_TOO_FEW_PRESENT"})
          String outcome,
      int withMeals,
      int presentWithMeals,
      int cancelledCount) {}

  /** The attendance state of a factory's day, for /admin/today and the banners. */
  public record AttendanceStatus(
      LocalDate date,
      boolean servingDay,
      Instant cutoffAt,
      @Nullable SyncRunSummary lastAttendanceSync,
      @Nullable SyncRunSummary lastLeaveSync,
      @Nullable CutoffSummary cutoff) {}

  public AttendanceStatus status(String factoryId, LocalDate date) {
    return new AttendanceStatus(
        date,
        isServingDay(factoryId, date),
        AttendanceRules.cutoffAt(date, cutoffTime(factoryId)),
        latestRun("ATTENDANCE", factoryId, date),
        latestRun("LEAVE", factoryId, null),
        cutoffOf(factoryId, date));
  }

  private SyncRunSummary latestRun(String type, String factoryId, LocalDate date) {
    return jdbc.sql(
            """
            SELECT id, status::text, "startedAt", "finishedAt", "recordCount", error
            FROM "SyncRun"
            WHERE type = CAST(:type AS "SyncType") AND "factoryId" = :factoryId
              AND (CAST(:date AS date) IS NULL OR date = :date)
            ORDER BY "startedAt" DESC, "finishedAt" DESC NULLS LAST, id DESC LIMIT 1
            """)
        .param("type", type)
        .param("factoryId", factoryId)
        .param("date", date)
        .query(
            (rs, i) ->
                new SyncRunSummary(
                    rs.getString("id"),
                    rs.getString("status"),
                    SqlTime.instant(rs, "startedAt"),
                    SqlTime.instant(rs, "finishedAt"),
                    (Integer) rs.getObject("recordCount"),
                    rs.getString("error")))
        .optional()
        .orElse(null);
  }

  private CutoffSummary cutoffOf(String factoryId, LocalDate date) {
    return jdbc.sql(
            """
            SELECT "cutoffRunAt", outcome::text, "withMeals", "presentWithMeals", "cancelledCount"
            FROM "AttendanceDay" WHERE "factoryId" = :factoryId AND date = :date
            """)
        .param("factoryId", factoryId)
        .param("date", date)
        .query(
            (rs, i) ->
                new CutoffSummary(
                    SqlTime.instant(rs, "cutoffRunAt"),
                    rs.getString("outcome"),
                    rs.getInt("withMeals"),
                    rs.getInt("presentWithMeals"),
                    rs.getInt("cancelledCount")))
        .optional()
        .orElse(null);
  }

  // --- attendance sync (BR-30, BR-32) ------------------------------------------------------------

  /**
   * @param restored BR-32: provisionally cancelled employees whose meals came back
   */
  public record SyncResult(
      String syncRunId,
      @io.swagger.v3.oas.annotations.media.Schema(allowableValues = {"SUCCESS", "FAILED"})
          String status,
      int records,
      int present,
      int restored,
      @Nullable String error) {}

  private record EmployeeRef(String id, String employeeCode) {}

  private record Seen(Instant firstCheckInAt, boolean confirmed) {}

  private record Row(
      String id,
      PresenceState state,
      Instant firstCheckInAt,
      Instant dmpConfirmedAt,
      boolean confirmedByAdmin) {}

  /**
   * Reads day D's attendance from DMP and updates presence. Present per DMP (BR-30) becomes
   * PRESENT; a provisionally cancelled employee who is now present is restored (BR-32). Nothing is
   * ever taken back by a sync.
   *
   * @param actorId null for the job; an admin's re-run is audited
   */
  public SyncResult syncAttendance(String factoryId, LocalDate date, String actorId) {
    String runId = startRun("ATTENDANCE", factoryId, date);
    if (actorId != null) {
      audit.log(AuditService.Entry.of(actorId, "sync.attendance.triggered", "SyncRun", runId));
    }
    try {
      List<DmpClient.AttendanceRecord> records = dmp.getAttendance(date);
      Map<String, List<Instant>> checkIns = new LinkedHashMap<>();
      Map<String, Boolean> confirmed = new HashMap<>();
      for (DmpClient.AttendanceRecord r : records) {
        List<Instant> list = checkIns.computeIfAbsent(r.employeeCode(), k -> new ArrayList<>());
        if (r.checkInAt() != null) {
          list.add(r.checkInAt());
        }
        confirmed.merge(r.employeeCode(), r.managerConfirmed(), Boolean::logicalOr);
      }
      Map<String, Seen> seen = new LinkedHashMap<>();
      checkIns.forEach(
          (code, list) ->
              seen.put(
                  code,
                  new Seen(
                      AttendanceRules.earliestCheckIn(list, date),
                      confirmed.getOrDefault(code, false))));
      LocalTime cutoff = cutoffTime(factoryId);
      int[] counts = tx.execute(status -> writeAttendance(factoryId, date, seen, cutoff));
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("employees", seen.size());
      details.put("present", counts[0]);
      details.put("restored", counts[1]);
      details.put("unknownEmployees", counts[2]);
      finishRun(runId, records.size(), details);
      return new SyncResult(runId, "SUCCESS", records.size(), counts[0], counts[1], null);
    } catch (RuntimeException e) {
      String message = failRun(runId, e);
      return new SyncResult(runId, "FAILED", 0, 0, 0, message);
    }
  }

  /**
   * @return present, restored, codes unknown to this factory
   */
  private int[] writeAttendance(
      String factoryId, LocalDate date, Map<String, Seen> seen, LocalTime cutoff) {
    lockDay(factoryId, date);
    Instant now = clock.now();
    // BR-32 (v1.4): after finalization a sync only records; ABSENT stays
    // (AttendanceRules.afterSync)
    // and a check-in seen for a cancelled employee is marked as a case to review.
    boolean finalized = days.isFinalized(factoryId, date);
    Map<String, EmployeeRef> byCode = new HashMap<>();
    if (!seen.isEmpty()) {
      jdbc.sql(
              """
              SELECT e.id, e."employeeCode" FROM "Employee" e
              JOIN "OrgUnit" o ON o.id = e."orgUnitId"
              WHERE o."factoryId" = :factoryId AND e."employeeCode" IN (:codes)
              """)
          .param("factoryId", factoryId)
          .param("codes", new ArrayList<>(seen.keySet()))
          .query(EmployeeRef.class)
          .list()
          .forEach(e -> byCode.put(e.employeeCode(), e));
    }
    Map<String, Row> rows = rowsOf(byCode.values().stream().map(EmployeeRef::id).toList(), date);
    int present = 0;
    int restored = 0;
    for (Map.Entry<String, Seen> entry : seen.entrySet()) {
      EmployeeRef e = byCode.get(entry.getKey());
      if (e == null) {
        continue;
      }
      Seen s = entry.getValue();
      Row row = rows.get(e.id());
      Instant first = earlier(row == null ? null : row.firstCheckInAt(), s.firstCheckInAt());
      Instant dmpConfirmedAt =
          row != null && row.dmpConfirmedAt() != null
              ? row.dmpConfirmedAt()
              : (s.confirmed() ? now : null);
      PresenceState before = row == null ? PresenceState.UNKNOWN : row.state();
      boolean inDmp = AttendanceRules.isPresent(first, dmpConfirmedAt != null, date, cutoff);
      PresenceState after = AttendanceRules.afterSync(before, inDmp);
      if (after == PresenceState.PRESENT) {
        present++;
      }
      boolean restoring =
          !finalized
              && before == PresenceState.PROVISIONAL_ABSENT
              && after == PresenceState.PRESENT;
      boolean lateCheckIn =
          finalized
              && before == PresenceState.ABSENT
              && first != null
              && !java.util.Objects.equals(first, row == null ? null : row.firstCheckInAt());
      if (row == null) {
        insertRow(e.id(), date, after, first, dmpConfirmedAt, now);
      } else if (after != before
          || !java.util.Objects.equals(first, row.firstCheckInAt())
          || !java.util.Objects.equals(dmpConfirmedAt, row.dmpConfirmedAt())) {
        jdbc.sql(
                """
                UPDATE "DailyPresence" SET state = CAST(:state AS "PresenceState"),
                    "firstCheckInAt" = :first, "dmpConfirmedAt" = :confirmed,
                    "restoredAt" = CASE WHEN :restoring THEN :now ELSE "restoredAt" END,
                    "lateCheckInAt" = CASE WHEN :late THEN COALESCE("lateCheckInAt", :now)
                                           ELSE "lateCheckInAt" END,
                    "updatedAt" = :now
                WHERE id = :id
                """)
            .param("state", after.name())
            .param("first", first == null ? null : SqlTime.utc(first))
            .param("confirmed", dmpConfirmedAt == null ? null : SqlTime.utc(dmpConfirmedAt))
            .param("restoring", restoring)
            .param("late", lateCheckIn)
            .param("now", SqlTime.utc(now))
            .param("id", row.id())
            .update();
      }
      if (restoring) {
        restored++;
        Map<String, Object> afterJson = new LinkedHashMap<>();
        afterJson.put("state", after.name());
        afterJson.put(
            "source",
            AttendanceRules.checkInInTime(first, date, cutoff) ? "CHECK_IN" : "DMP_CONFIRMATION");
        audit.log(
            AuditService.Entry.of(
                    null, "presence.restored", "DailyPresence", e.employeeCode() + ":" + date)
                .withBefore(Map.of("state", before.name()))
                .withAfter(afterJson));
      }
    }
    int unknown = (int) seen.keySet().stream().filter(c -> !byCode.containsKey(c)).count();
    return new int[] {present, restored, unknown};
  }

  private static Instant earlier(Instant a, Instant b) {
    if (a == null) {
      return b;
    }
    return b == null || a.isBefore(b) ? a : b;
  }

  private Map<String, Row> rowsOf(List<String> employeeIds, LocalDate date) {
    Map<String, Row> out = new HashMap<>();
    if (employeeIds.isEmpty()) {
      return out;
    }
    jdbc.sql(
            """
            SELECT id, "employeeId", state::text, "firstCheckInAt", "dmpConfirmedAt",
                   "confirmedAt" IS NOT NULL AS "confirmedByAdmin"
            FROM "DailyPresence" WHERE date = :date AND "employeeId" IN (:ids)
            """)
        .param("date", date)
        .param("ids", employeeIds)
        .query(
            (rs, i) ->
                out.put(
                    rs.getString("employeeId"),
                    new Row(
                        rs.getString("id"),
                        PresenceState.valueOf(rs.getString("state")),
                        SqlTime.instant(rs, "firstCheckInAt"),
                        SqlTime.instant(rs, "dmpConfirmedAt"),
                        rs.getBoolean("confirmedByAdmin"))))
        .list();
    return out;
  }

  private void insertRow(
      String employeeId,
      LocalDate date,
      PresenceState state,
      Instant first,
      Instant dmpConfirmedAt,
      Instant now) {
    jdbc.sql(
            """
            INSERT INTO "DailyPresence" (id, "employeeId", date, state, "firstCheckInAt",
                "dmpConfirmedAt", "provisionalAt", "updatedAt")
            VALUES (:id, :employeeId, :date, CAST(:state AS "PresenceState"), :first, :confirmed,
                :provisional, :now)
            """)
        .param("id", Ids.newId(now.toEpochMilli()))
        .param("employeeId", employeeId)
        .param("date", date)
        .param("state", state.name())
        .param("first", first == null ? null : SqlTime.utc(first))
        .param("confirmed", dmpConfirmedAt == null ? null : SqlTime.utc(dmpConfirmedAt))
        .param("provisional", state == PresenceState.PROVISIONAL_ABSENT ? SqlTime.utc(now) : null)
        .param("now", SqlTime.utc(now))
        .update();
  }

  private void lockDay(String factoryId, LocalDate date) {
    days.lockDay(factoryId, date);
  }

  // --- cutoff (BR-31, BR-34) ---------------------------------------------------------------------

  public record CutoffResult(
      @io.swagger.v3.oas.annotations.media.Schema(
              allowableValues = {"CANCELLED", "HELD_SYNC_FAILED", "HELD_TOO_FEW_PRESENT"})
          String outcome,
      int withMeals,
      int presentWithMeals,
      int cancelledCount) {}

  /**
   * BR-31 with the BR-34 safety check, once per day. After a held run only the HC admin's re-run
   * ({@code reevaluate}) tries again; a later poll never cancels by itself (BR-34: "the HC admin
   * re-runs the sync or cancels manually").
   */
  public CutoffResult runCutoff(
      String factoryId, LocalDate date, String actorId, boolean reevaluate) {
    return tx.execute(
        status -> {
          lockDay(factoryId, date);
          CutoffSummary existing = cutoffOf(factoryId, date);
          // BR-43: nothing is cancelled after finalization; the outcome stays as it was.
          boolean finalized = days.isFinalized(factoryId, date);
          if (existing != null
              && (finalized
                  || !reevaluate
                  || existing.outcome().equals(CutoffOutcome.CANCELLED.name()))) {
            return new CutoffResult(
                existing.outcome(),
                existing.withMeals(),
                existing.presentWithMeals(),
                existing.cancelledCount());
          }
          if (finalized) {
            return null;
          }
          Instant now = clock.now();
          SyncRunSummary latest = latestFinishedRun(factoryId, date);
          List<EmployeeRef> withMeals = employeesWithMeals(factoryId, date);
          Map<String, Row> rows = rowsOf(withMeals.stream().map(EmployeeRef::id).toList(), date);
          int present =
              (int)
                  withMeals.stream()
                      .map(e -> rows.get(e.id()))
                      .filter(r -> r != null && r.state() == PresenceState.PRESENT)
                      .count();
          double minPct =
              settings.get(factoryId, SettingKey.ATTENDANCE_SAFETY_MIN_PRESENT_PCT).asDouble();
          CutoffOutcome outcome =
              AttendanceRules.safetyCheck(
                  latest != null && latest.status().equals("SUCCESS"),
                  withMeals.size(),
                  present,
                  minPct);
          int cancelled = 0;
          if (outcome == CutoffOutcome.CANCELLED) {
            for (EmployeeRef e : withMeals) {
              Row row = rows.get(e.id());
              PresenceState before = row == null ? PresenceState.UNKNOWN : row.state();
              if (!AttendanceRules.cancelledAtCutoff(before, true)) {
                continue;
              }
              if (row == null) {
                insertRow(e.id(), date, PresenceState.PROVISIONAL_ABSENT, null, null, now);
              } else {
                jdbc.sql(
                        """
                        UPDATE "DailyPresence" SET state = 'PROVISIONAL_ABSENT',
                            "provisionalAt" = :now, "updatedAt" = :now
                        WHERE id = :id
                        """)
                    .param("now", SqlTime.utc(now))
                    .param("id", row.id())
                    .update();
              }
              cancelled++;
              audit.log(
                  AuditService.Entry.of(
                          null,
                          "presence.provisionallyCancelled",
                          "DailyPresence",
                          e.employeeCode() + ":" + date)
                      .withBefore(Map.of("state", before.name()))
                      .withAfter(Map.of("state", PresenceState.PROVISIONAL_ABSENT.name())));
            }
          }
          jdbc.sql(
                  """
                  INSERT INTO "AttendanceDay" (id, "factoryId", date, "cutoffRunAt", outcome,
                      "withMeals", "presentWithMeals", "cancelledCount", "syncRunId")
                  VALUES (:id, :factoryId, :date, :now, CAST(:outcome AS "CutoffOutcome"),
                      :withMeals, :present, :cancelled, :syncRunId)
                  ON CONFLICT ("factoryId", date) DO UPDATE SET "cutoffRunAt" = :now,
                      outcome = CAST(:outcome AS "CutoffOutcome"), "withMeals" = :withMeals,
                      "presentWithMeals" = :present, "cancelledCount" = :cancelled,
                      "syncRunId" = :syncRunId
                  """)
              .param("id", Ids.newId(now.toEpochMilli()))
              .param("factoryId", factoryId)
              .param("date", date)
              .param("now", SqlTime.utc(now))
              .param("outcome", outcome.name())
              .param("withMeals", withMeals.size())
              .param("present", present)
              .param("cancelled", cancelled)
              .param("syncRunId", latest == null ? null : latest.id())
              .update();
          Map<String, Object> after = new LinkedHashMap<>();
          after.put("outcome", outcome.name());
          after.put("withMeals", withMeals.size());
          after.put("presentWithMeals", present);
          after.put("cancelled", cancelled);
          audit.log(
              AuditService.Entry.of(
                      actorId, "attendance.cutoff", "AttendanceDay", factoryId + ":" + date)
                  .withAfter(after));
          if (outcome != CutoffOutcome.CANCELLED) {
            log.warn(
                "Attendance cutoff {} {} held ({}): {} of {} employees with meals present",
                factoryId,
                date,
                outcome,
                present,
                withMeals.size());
          }
          return new CutoffResult(outcome.name(), withMeals.size(), present, cancelled);
        });
  }

  private SyncRunSummary latestFinishedRun(String factoryId, LocalDate date) {
    return jdbc.sql(
            """
            SELECT id, status::text, "startedAt", "finishedAt", "recordCount", error
            FROM "SyncRun"
            WHERE type = 'ATTENDANCE' AND "factoryId" = :factoryId AND date = :date
              AND status <> 'RUNNING'
            ORDER BY "startedAt" DESC, "finishedAt" DESC NULLS LAST, id DESC LIMIT 1
            """)
        .param("factoryId", factoryId)
        .param("date", date)
        .query(
            (rs, i) ->
                new SyncRunSummary(
                    rs.getString("id"),
                    rs.getString("status"),
                    SqlTime.instant(rs, "startedAt"),
                    SqlTime.instant(rs, "finishedAt"),
                    (Integer) rs.getObject("recordCount"),
                    rs.getString("error")))
        .optional()
        .orElse(null);
  }

  /** Employees of the factory with an active lunch on day D. */
  private List<EmployeeRef> employeesWithMeals(String factoryId, LocalDate date) {
    return jdbc.sql(
            """
            SELECT DISTINCT e.id, e."employeeCode" FROM "Registration" r
            JOIN "Employee" e ON e.id = r."employeeId"
            JOIN "OrgUnit" o ON o.id = e."orgUnitId"
            WHERE o."factoryId" = :factoryId AND r.date = :date AND r.session = 'LUNCH'
              AND r.status = 'ACTIVE' AND r.quantity > 0
            ORDER BY e."employeeCode"
            """)
        .param("factoryId", factoryId)
        .param("date", date)
        .query(EmployeeRef.class)
        .list();
  }

  // --- jobs and catch-up (plan 6.5) --------------------------------------------------------------

  /**
   * The poll-attendance and provisional-cancel jobs for today: a sync when one is due (BR-30), and
   * the cutoff run once 08:10 has passed (BR-31), right after a fresh sync.
   */
  public void tick(String factoryId) {
    Instant now = clock.now();
    LocalDate today = VnTime.vnDateOf(now);
    if (!isServingDay(factoryId, today)) {
      return;
    }
    LocalTime cutoff = cutoffTime(factoryId);
    SyncRunSummary last = latestRun("ATTENDANCE", factoryId, today);
    boolean finalized = days.isFinalized(factoryId, today);
    boolean cutoffDue =
        AttendanceRules.isCutoffDue(
            now, today, cutoff, cutoffOf(factoryId, today) != null, finalized);
    boolean pollDue =
        AttendanceRules.isPollDue(
            now,
            today,
            settings.getTime(factoryId, SettingKey.ATTENDANCE_POLL_START),
            settings.getInt(factoryId, SettingKey.ATTENDANCE_POLL_INTERVAL_MIN),
            last == null ? null : last.startedAt(),
            finalized,
            days.adjustmentDeadline(factoryId));
    boolean staleForCutoff =
        cutoffDue
            && (last == null || last.startedAt().isBefore(AttendanceRules.cutoffAt(today, cutoff)));
    if (pollDue || staleForCutoff) {
      SyncResult result = syncAttendance(factoryId, today, null);
      if (result.status().equals("FAILED")) {
        log.error("Attendance sync {} {} failed: {}", factoryId, today, result.error());
      }
    }
    if (cutoffDue) {
      runCutoff(factoryId, today, null, false);
    }
  }

  /**
   * Plan 6.5: a missed cutoff run is done by the first request after 08:10. It reads no DMP data:
   * if no sync succeeded, BR-34 holds the cancellation.
   */
  public void catchUpCutoff(String factoryId, LocalDate date) {
    Instant now = clock.now();
    if (AttendanceRules.isCutoffDue(
            now,
            date,
            cutoffTime(factoryId),
            cutoffOf(factoryId, date) != null,
            days.isFinalized(factoryId, date))
        && isServingDay(factoryId, date)) {
      runCutoff(factoryId, date, null, false);
    }
  }

  /**
   * The HC admin's re-run (plan section 3, BR-34): attendance and leave from DMP now, then a held
   * cutoff is evaluated again. A failed read answers 502.
   */
  public RerunResult rerun(String factoryId, LocalDate date, String actorId) {
    // BR-32 (v1.4): after finalization the re-run only records, until close.adjustmentDeadline;
    // from then on no sync runs for that day.
    boolean finalized = days.isFinalized(factoryId, date);
    if (finalized
        && !clock
            .now()
            .isBefore(
                DayCloseRules.adjustmentDeadlineAt(date, days.adjustmentDeadline(factoryId)))) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          ApiErrorCode.DAY_FINALIZED,
          "No attendance sync after close.adjustmentDeadline of a finalized day (BR-32)",
          Map.of("date", date.toString()),
          null);
    }
    SyncResult attendance = syncAttendance(factoryId, date, actorId);
    if (attendance.status().equals("FAILED")) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("syncRunId", attendance.syncRunId());
      details.put("error", attendance.error());
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          ApiErrorCode.DMP_UNAVAILABLE,
          "Attendance sync failed",
          details,
          null);
    }
    LeaveResult leave = syncLeave(factoryId, actorId);
    CutoffResult cutoff = null;
    if (!finalized
        && !clock.now().isBefore(AttendanceRules.cutoffAt(date, cutoffTime(factoryId)))
        && VnTime.vnDateOf(clock.now()).equals(date)
        && isServingDay(factoryId, date)) {
      cutoff = runCutoff(factoryId, date, actorId, true);
    }
    return new RerunResult(attendance, leave, cutoff);
  }

  public record RerunResult(
      SyncResult attendance, LeaveResult leave, @Nullable CutoffResult cutoff) {}

  // --- leave (BR-35) -----------------------------------------------------------------------------

  public record LeaveResult(
      String syncRunId,
      @io.swagger.v3.oas.annotations.media.Schema(allowableValues = {"SUCCESS", "FAILED"})
          String status,
      int flags,
      @Nullable String error) {}

  /**
   * BR-35: leave flags from today to 14 days ahead. A flag DMP no longer returns is marked removed,
   * never deleted (rule 7). Flags never change meals.
   */
  public LeaveResult syncLeave(String factoryId, String actorId) {
    LocalDate from = VnTime.vnDateOf(clock.now());
    LocalDate to = from.plusDays(LEAVE_DAYS_AHEAD);
    String runId = startRun("LEAVE", factoryId, null);
    if (actorId != null) {
      audit.log(AuditService.Entry.of(actorId, "sync.leave.triggered", "SyncRun", runId));
    }
    try {
      List<DmpClient.LeaveRecord> records = dmp.getLeave(from, to);
      Map<String, DmpClient.LeaveRecord> merged = new LinkedHashMap<>();
      for (DmpClient.LeaveRecord r : records) {
        merged.merge(r.employeeCode() + "|" + r.date(), r, AttendanceService::mergeLeave);
      }
      int written = tx.execute(status -> writeLeave(factoryId, from, to, merged.values()));
      finishRun(runId, records.size(), Map.of("flags", written));
      return new LeaveResult(runId, "SUCCESS", written, null);
    } catch (RuntimeException e) {
      return new LeaveResult(runId, "FAILED", 0, failRun(runId, e));
    }
  }

  /** Morning plus afternoon leave on the same day is a full day. */
  private static DmpClient.LeaveRecord mergeLeave(
      DmpClient.LeaveRecord a, DmpClient.LeaveRecord b) {
    if (a.dayType() == b.dayType()) {
      return a;
    }
    return new DmpClient.LeaveRecord(
        a.employeeCode(), a.date(), DmpClient.LeaveDayType.FULL, a.leaveTypeName());
  }

  private int writeLeave(
      String factoryId,
      LocalDate from,
      LocalDate to,
      java.util.Collection<DmpClient.LeaveRecord> records) {
    Instant now = clock.now();
    Map<String, String> idByCode = new HashMap<>();
    List<String> codes =
        records.stream().map(DmpClient.LeaveRecord::employeeCode).distinct().toList();
    if (!codes.isEmpty()) {
      jdbc.sql(
              """
              SELECT e.id, e."employeeCode" FROM "Employee" e
              JOIN "OrgUnit" o ON o.id = e."orgUnitId"
              WHERE o."factoryId" = :factoryId AND e."employeeCode" IN (:codes)
              """)
          .param("factoryId", factoryId)
          .param("codes", codes)
          .query(EmployeeRef.class)
          .list()
          .forEach(e -> idByCode.put(e.employeeCode(), e.id()));
    }
    int written = 0;
    for (DmpClient.LeaveRecord r : records) {
      String employeeId = idByCode.get(r.employeeCode());
      if (employeeId == null || r.date().isBefore(from) || r.date().isAfter(to)) {
        continue;
      }
      jdbc.sql(
              """
              INSERT INTO "LeaveFlag" (id, "employeeId", date, "dayType", "leaveTypeName",
                  "syncedAt")
              VALUES (:id, :employeeId, :date, CAST(:dayType AS "LeaveDayType"), :name, :now)
              ON CONFLICT ("employeeId", date) DO UPDATE SET
                  "dayType" = CAST(:dayType AS "LeaveDayType"), "leaveTypeName" = :name,
                  "syncedAt" = :now, "removedAt" = NULL
              """)
          .param("id", Ids.newId(now.toEpochMilli()))
          .param("employeeId", employeeId)
          .param("date", r.date())
          .param("dayType", r.dayType().name())
          .param("name", r.leaveTypeName())
          .param("now", SqlTime.utc(now))
          .update();
      written++;
    }
    jdbc.sql(
            """
            UPDATE "LeaveFlag" f SET "removedAt" = :now
            FROM "Employee" e JOIN "OrgUnit" o ON o.id = e."orgUnitId"
            WHERE f."employeeId" = e.id AND o."factoryId" = :factoryId
              AND f.date BETWEEN :from AND :to AND f."removedAt" IS NULL AND f."syncedAt" < :now
            """)
        .param("now", SqlTime.utc(now))
        .param("factoryId", factoryId)
        .param("from", from)
        .param("to", to)
        .update();
    return written;
  }

  // --- presence confirmation (BR-33) -------------------------------------------------------------

  /**
   * BR-33: a dept or HC admin confirms an employee present on day D until finalization; the meals
   * are restored. The reason is optional (v1.3); the confirmation is always audited. Confirming
   * twice changes nothing.
   *
   * @param reason null or blank when none was given
   */
  public Presence confirm(
      String actorId,
      String factoryId,
      String employeeId,
      String employeeCode,
      LocalDate date,
      String reason) {
    if (!AttendanceRules.canConfirmPresence(
        clock.now(),
        date,
        days.isFinalized(factoryId, date),
        days.autoFinalizeAt(factoryId, date))) {
      throw confirmClosed(factoryId, date);
    }
    String cleanReason = reason == null || reason.isBlank() ? null : reason.strip();
    tx.executeWithoutResult(
        status -> {
          lockDay(factoryId, date);
          Instant now = clock.now();
          if (days.isFinalized(factoryId, date)) {
            throw confirmClosed(factoryId, date);
          }
          Row row = rowsOf(List.of(employeeId), date).get(employeeId);
          if (row != null && row.confirmedByAdmin()) {
            return;
          }
          PresenceState before = row == null ? PresenceState.UNKNOWN : row.state();
          if (row == null) {
            insertRow(employeeId, date, PresenceState.PRESENT, null, null, now);
          }
          jdbc.sql(
                  """
                  UPDATE "DailyPresence" SET state = 'PRESENT', "confirmedById" = :actorId,
                      "confirmedAt" = :now, "confirmReason" = :reason,
                      "restoredAt" = CASE WHEN state = 'PROVISIONAL_ABSENT' THEN :now
                                          ELSE "restoredAt" END,
                      "updatedAt" = :now
                  WHERE "employeeId" = :employeeId AND date = :date
                  """)
              .param("actorId", actorId)
              .param("now", SqlTime.utc(now))
              .param("reason", cleanReason)
              .param("employeeId", employeeId)
              .param("date", date)
              .update();
          audit.log(
              AuditService.Entry.of(
                      actorId, "presence.confirmed", "DailyPresence", employeeCode + ":" + date)
                  .withBefore(Map.of("state", before.name()))
                  .withAfter(Map.of("state", PresenceState.PRESENT.name()))
                  .withReason(cleanReason));
        });
    return presenceOf(List.of(employeeId), date).get(employeeId);
  }

  private ApiException confirmClosed(String factoryId, LocalDate date) {
    boolean finalized = days.isFinalized(factoryId, date);
    return new ApiException(
        HttpStatus.CONFLICT,
        finalized ? ApiErrorCode.DAY_FINALIZED : ApiErrorCode.DAY_LOCKED,
        "Presence can only be confirmed on day D until finalization (BR-33)",
        Map.of("date", date.toString()),
        null);
  }

  // --- SyncRun -----------------------------------------------------------------------------------

  private String startRun(String type, String factoryId, LocalDate date) {
    Instant now = clock.now();
    String runId = Ids.newId(now.toEpochMilli());
    jdbc.sql(
            """
            INSERT INTO "SyncRun" (id, type, "startedAt", status, "factoryId", date)
            VALUES (:id, CAST(:type AS "SyncType"), :now, 'RUNNING', :factoryId, :date)
            """)
        .param("id", runId)
        .param("type", type)
        .param("now", SqlTime.utc(now))
        .param("factoryId", factoryId)
        .param("date", date)
        .update();
    return runId;
  }

  private void finishRun(String runId, int count, Map<String, Object> details) {
    jdbc.sql(
            """
            UPDATE "SyncRun" SET status = 'SUCCESS', "finishedAt" = :now, "recordCount" = :count,
                   details = CAST(:details AS jsonb)
            WHERE id = :id
            """)
        .param("now", SqlTime.utc(clock.now()))
        .param("count", count)
        .param("details", json.writeValueAsString(details))
        .param("id", runId)
        .update();
  }

  private String failRun(String runId, RuntimeException e) {
    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    log.error("Sync {} failed: {}", runId, message);
    jdbc.sql(
            """
            UPDATE "SyncRun" SET status = 'FAILED', "finishedAt" = :now, error = :error
            WHERE id = :id
            """)
        .param("now", SqlTime.utc(clock.now()))
        .param("error", message.length() > 2000 ? message.substring(0, 2000) : message)
        .param("id", runId)
        .update();
    return message;
  }
}
