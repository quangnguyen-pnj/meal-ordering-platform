package vn.pnj.meal.api.close;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.pnj.meal.api.attendance.AttendanceService;
import vn.pnj.meal.api.audit.AuditService;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.common.ApiErrorCode;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.db.Ids;
import vn.pnj.meal.api.db.SqlTime;
import vn.pnj.meal.api.org.OrgDirectory;
import vn.pnj.meal.api.org.OrgSnapshot;
import vn.pnj.meal.api.registrations.ForecastService;
import vn.pnj.meal.api.settings.SettingKey;
import vn.pnj.meal.api.settings.SettingsService;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.attendance.AttendanceRules.CutoffOutcome;
import vn.pnj.meal.rules.close.DayCloseRules;
import vn.pnj.meal.rules.close.DayCloseRules.FinalizeCheck;
import vn.pnj.meal.rules.org.Mapping;
import vn.pnj.meal.rules.org.Mapping.EmployeeMealAssignment;

/**
 * BR-43: finalization of a factory's day. Provisional cancellations become cancellations, the
 * per-employee snapshot ({@code FinalEmployeeLine}) and the count by unit x meal ({@code
 * FinalCount}) are written once, and the day is locked: normal edits are refused from then on, and
 * only HC admin adjustments remain (BR-46).
 *
 * <p>The HC admin finalizes from {@code close.time}; a serving day not finalized by {@code
 * close.autoFinalizeAt} is finalized by the system (product owner, 2026-11-10), by the close-day
 * job or, if it was missed, by the next request that needs the day (plan 6.5).
 */
@Service
public class DayCloseService {
  private static final Logger log = LoggerFactory.getLogger(DayCloseService.class);

  /** Plan 6.5 catch-up: missed auto-finalizations are looked for this many days back. */
  static final int CATCH_UP_DAYS = 7;

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batch;
  private final TransactionTemplate tx;
  private final ClockService clock;
  private final AuditService audit;
  private final SettingsService settings;
  private final OrgDirectory org;
  private final ForecastService forecast;
  private final AttendanceService attendance;
  private final DayCloseStore days;

  public DayCloseService(
      JdbcClient jdbc,
      NamedParameterJdbcTemplate batch,
      TransactionTemplate tx,
      ClockService clock,
      AuditService audit,
      SettingsService settings,
      OrgDirectory org,
      ForecastService forecast,
      AttendanceService attendance,
      DayCloseStore days) {
    this.jdbc = jdbc;
    this.batch = batch;
    this.tx = tx;
    this.clock = clock;
    this.audit = audit;
    this.settings = settings;
    this.org = org;
    this.forecast = forecast;
    this.attendance = attendance;
    this.days = days;
  }

  /**
   * @param cutoffOutcome the BR-34 state of the cutoff at finalization, null when it never ran
   */
  public record FinalizeResult(
      LocalDate date,
      Instant finalizedAt,
      boolean autoFinalized,
      int cancelled,
      int employees,
      int meals,
      // An enum, not a String with @Schema(allowableValues): with @Nullable that pair makes
      // springdoc drop the whole response schema.
      @Nullable CutoffOutcome cutoffOutcome) {}

  /**
   * BR-43: finalizes day D.
   *
   * @param actorId the HC admin; null when the system finalizes at {@code close.autoFinalizeAt}
   */
  public FinalizeResult finalizeDay(@Nullable String actorId, String factoryId, LocalDate date) {
    if (!attendance.isServingDay(factoryId, date)) {
      throw ApiException.invalidState("Not a serving day", Map.of("date", date.toString()));
    }
    // Whatever was due before finalization happens first (plan 6.5): the forecast snapshot, the
    // BR-14 cancellations at the lock and a missed cutoff run.
    forecast.catchUp(factoryId);
    attendance.catchUpCutoff(factoryId, date);
    check(factoryId, date);
    FinalizeResult result = tx.execute(status -> write(actorId, factoryId, date));
    log.info(
        "Day {} {} finalized{}: {} meals, {} cancelled",
        factoryId,
        date,
        actorId == null ? " automatically" : "",
        result.meals(),
        result.cancelled());
    return result;
  }

  private void check(String factoryId, LocalDate date) {
    FinalizeCheck check =
        DayCloseRules.canFinalize(
            clock.now(),
            date,
            settings.getTime(factoryId, SettingKey.CLOSE_TIME),
            settings.getTime(factoryId, SettingKey.DAY_LOCK_TIME),
            days.isFinalized(factoryId, date));
    switch (check) {
      case OK -> {}
      case ALREADY_FINALIZED ->
          throw new ApiException(
              HttpStatus.CONFLICT,
              ApiErrorCode.DAY_FINALIZED,
              "Day is already finalized",
              Map.of("date", date.toString()),
              null);
      case TOO_EARLY ->
          throw new ApiException(
              HttpStatus.CONFLICT,
              ApiErrorCode.TOO_EARLY,
              "Finalization opens at close.time (BR-43)",
              Map.of(
                  "date",
                  date.toString(),
                  "opensAt",
                  days.finalizeOpensAt(factoryId, date).toString()),
              null);
    }
  }

  private record RegLine(
      String employeeId,
      String employeeCode,
      String menuItemId,
      String canteenId,
      int quantity,
      String orgUnitId,
      String floorOverrideId,
      String dmpCostCenterCode) {}

  private record Absent(String id, String employeeCode) {}

  private FinalizeResult write(@Nullable String actorId, String factoryId, LocalDate date) {
    // Registration writes hold their week FOR SHARE; taking it exclusively here means an admin
    // edit lands either before the snapshot (and is in it) or after (and is refused).
    jdbc.sql(
            """
            SELECT w.id FROM "MenuWeek" w
            WHERE w."factoryId" = :factoryId AND w."weekStart" = :weekStart
            ORDER BY w.id FOR UPDATE
            """)
        .param("factoryId", factoryId)
        .param("weekStart", VnTime.mondayOf(date))
        .query(String.class)
        .list();
    days.lockDay(factoryId, date);
    check(factoryId, date);
    Instant now = clock.now();
    String dayCloseId = Ids.newId(now.toEpochMilli());
    AttendanceService.CutoffSummary cutoff = attendance.status(factoryId, date).cutoff();

    // BR-43: provisional cancellations become cancellations, each audited with its actor (BR-70).
    List<Absent> absent =
        jdbc.sql(
                """
                UPDATE "DailyPresence" p SET state = 'ABSENT', "absentAt" = :now,
                    "updatedAt" = :now
                FROM "Employee" e JOIN "OrgUnit" o ON o.id = e."orgUnitId"
                WHERE p."employeeId" = e.id AND o."factoryId" = :factoryId AND p.date = :date
                  AND p.state = 'PROVISIONAL_ABSENT'
                RETURNING p.id, e."employeeCode"
                """)
            .param("now", SqlTime.utc(now))
            .param("factoryId", factoryId)
            .param("date", date)
            .query(Absent.class)
            .list();
    for (Absent a : absent) {
      audit.log(
          AuditService.Entry.of(
                  actorId, "presence.cancelled", "DailyPresence", a.employeeCode() + ":" + date)
              .withBefore(Map.of("state", "PROVISIONAL_ABSENT"))
              .withAfter(Map.of("state", "ABSENT")));
    }

    jdbc.sql(
            """
            INSERT INTO "DayClose" (id, "factoryId", date, session, status, "finalizedById",
                "finalizedAt", "autoFinalized", "cutoffOutcome", "cancelledCount")
            VALUES (:id, :factoryId, :date, 'LUNCH', 'FINALIZED', :actorId, :now, :auto,
                CAST(:outcome AS "CutoffOutcome"), :cancelled)
            """)
        .param("id", dayCloseId)
        .param("factoryId", factoryId)
        .param("date", date)
        .param("actorId", actorId)
        .param("now", SqlTime.utc(now))
        .param("auto", actorId == null)
        .param("outcome", cutoff == null ? null : cutoff.outcome())
        .param("cancelled", absent.size())
        .update();

    // Meals that count once presence is applied (plan 6.3: actual = active registrations and day-D
    // presence); the canteen is the one whose menu the meal was taken from (BR-15).
    List<RegLine> lines =
        jdbc.sql(
                """
                SELECT r."employeeId", e."employeeCode", r."menuItemId", w."canteenId",
                       r.quantity, e."orgUnitId", e."floorOverrideId", e."dmpCostCenterCode"
                FROM "Registration" r
                JOIN "Employee" e ON e.id = r."employeeId"
                JOIN "OrgUnit" o ON o.id = e."orgUnitId"
                JOIN "MenuItem" i ON i.id = r."menuItemId"
                JOIN "MenuDay" d ON d.id = i."menuDayId"
                JOIN "MenuWeek" w ON w.id = d."menuWeekId"
                LEFT JOIN "DailyPresence" p ON p."employeeId" = e.id AND p.date = r.date
                WHERE o."factoryId" = :factoryId AND r.date = :date AND r.session = 'LUNCH'
                  AND r.status = 'ACTIVE' AND r.quantity > 0
                  AND (p.state IS NULL OR p.state NOT IN ('ABSENT', 'PROVISIONAL_ABSENT'))
                ORDER BY e."employeeCode", r."menuItemId"
                """)
            .param("factoryId", factoryId)
            .param("date", date)
            .query(RegLine.class)
            .list();
    OrgSnapshot snapshot = org.load(factoryId);
    Map<String, String> ccByCode = org.costCenterIdsByCode(factoryId);
    Map<String, EmployeeMealAssignment> mapping = new HashMap<>();
    for (RegLine l : lines) {
      mapping.computeIfAbsent(
          l.employeeId(),
          k ->
              Mapping.resolveEmployeeMealAssignment(
                  l.orgUnitId(),
                  l.floorOverrideId(),
                  l.dmpCostCenterCode() == null ? null : ccByCode.get(l.dmpCostCenterCode()),
                  snapshot.index()));
    }
    MapSqlParameterSource[] rows =
        lines.stream()
            .map(
                l -> {
                  EmployeeMealAssignment a = mapping.get(l.employeeId());
                  return new MapSqlParameterSource()
                      .addValue("id", Ids.newId(now.toEpochMilli()))
                      .addValue("dayCloseId", dayCloseId)
                      .addValue("employeeId", l.employeeId())
                      .addValue("menuItemId", l.menuItemId())
                      .addValue("quantity", l.quantity())
                      .addValue("mealUnitId", a.mealUnitId())
                      .addValue("floorId", a.floorId())
                      .addValue("canteenId", l.canteenId())
                      .addValue("costCenterId", a.costCenterId())
                      .addValue("now", SqlTime.utc(now));
                })
            .toArray(MapSqlParameterSource[]::new);
    if (rows.length > 0) {
      batch.batchUpdate(
          """
          INSERT INTO "FinalEmployeeLine" (id, "dayCloseId", "employeeId", "menuItemId", quantity,
              "mealUnitId", "floorId", "canteenId", "costCenterId", "createdAt")
          VALUES (:id, :dayCloseId, :employeeId, :menuItemId, :quantity, :mealUnitId, :floorId,
              :canteenId, :costCenterId, :now)
          """,
          rows);
    }
    // FinalCount = sum of FinalEmployeeLine by unit x floor x canteen x meal (plan 6.3).
    jdbc.sql(
            """
            INSERT INTO "FinalCount" (id, "dayCloseId", "mealUnitId", "floorId", "canteenId",
                "menuItemId", quantity)
            SELECT :prefix || row_number() OVER (), "dayCloseId", "mealUnitId", "floorId",
                   "canteenId", "menuItemId", SUM(quantity)
            FROM "FinalEmployeeLine" WHERE "dayCloseId" = :dayCloseId
            GROUP BY "dayCloseId", "mealUnitId", "floorId", "canteenId", "menuItemId"
            """)
        .param("prefix", dayCloseId + "_")
        .param("dayCloseId", dayCloseId)
        .update();

    int meals = lines.stream().mapToInt(RegLine::quantity).sum();
    int employees = new LinkedHashSet<>(lines.stream().map(RegLine::employeeId).toList()).size();
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("date", date.toString());
    after.put("automatic", actorId == null);
    after.put("meals", meals);
    after.put("employees", employees);
    after.put("cancelled", absent.size());
    after.put("cutoffOutcome", cutoff == null ? null : cutoff.outcome());
    audit.log(
        AuditService.Entry.of(actorId, "day.finalized", "DayClose", dayCloseId).withAfter(after));
    return new FinalizeResult(
        date,
        now,
        actorId == null,
        absent.size(),
        employees,
        meals,
        cutoff == null ? null : CutoffOutcome.valueOf(cutoff.outcome()));
  }

  /**
   * Product owner, 2026-11-10: serving days of the last {@value #CATCH_UP_DAYS} days that are not
   * finalized by {@code close.autoFinalizeAt} are finalized by the system. Idempotent; called by
   * the close-day job and before reads and writes that depend on the day being final.
   *
   * @return the days finalized by this call
   */
  public List<LocalDate> finalizeDue(String factoryId) {
    Instant now = clock.now();
    LocalDate today = VnTime.vnDateOf(now);
    List<LocalDate> candidates =
        jdbc.sql(
                """
                SELECT DISTINCT d.date FROM "MenuDay" d
                JOIN "MenuWeek" w ON w.id = d."menuWeekId"
                WHERE w."factoryId" = :factoryId AND w.status <> 'DRAFT' AND d."isServing"
                  AND d.date BETWEEN :from AND :today
                  AND NOT EXISTS (SELECT 1 FROM "DayClose" c WHERE c."factoryId" = :factoryId
                                    AND c.date = d.date AND c.session = 'LUNCH')
                ORDER BY d.date
                """)
            .param("factoryId", factoryId)
            .param("from", today.minusDays(CATCH_UP_DAYS))
            .param("today", today)
            .query(LocalDate.class)
            .list();
    List<LocalDate> done = new ArrayList<>();
    for (LocalDate date : candidates) {
      if (!DayCloseRules.isAutoFinalizeDue(now, days.autoFinalizeAt(factoryId, date), false)) {
        continue;
      }
      try {
        finalizeDay(null, factoryId, date);
        done.add(date);
      } catch (ApiException e) {
        // Finalized meanwhile by the HC admin or another node: nothing left to do.
        if (e.code() != ApiErrorCode.DAY_FINALIZED) {
          throw e;
        }
      }
    }
    return done;
  }

  /** BR-43: the HC admin is reminded from {@code close.reminderAt} until today is finalized. */
  public boolean reminderDue(String factoryId) {
    Instant now = clock.now();
    LocalDate today = VnTime.vnDateOf(now);
    return attendance.isServingDay(factoryId, today)
        && DayCloseRules.isReminderDue(
            now,
            today,
            settings.getTime(factoryId, SettingKey.CLOSE_REMINDER_AT),
            days.isFinalized(factoryId, today));
  }
}
