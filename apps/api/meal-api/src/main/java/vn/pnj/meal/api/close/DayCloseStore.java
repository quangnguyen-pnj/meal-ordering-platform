package vn.pnj.meal.api.close;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.pnj.meal.api.common.ApiErrorCode;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.db.SqlTime;
import vn.pnj.meal.api.settings.SettingKey;
import vn.pnj.meal.api.settings.SettingsService;
import vn.pnj.meal.rules.close.DayCloseRules;
import vn.pnj.meal.rules.registration.RegistrationRules;
import vn.pnj.meal.rules.registration.RegistrationRules.AdminDayWindow;

/**
 * Reads of a day's finalization (BR-43), for every flow whose window ends "at finalization":
 * registrations by admins (BR-24, BR-25), presence (BR-33), attendance (BR-30, BR-32) and
 * adjustments (BR-46). Also the per-day lock that orders attendance writes, admin confirmations and
 * the finalization itself.
 */
@Component
public class DayCloseStore {
  /** Postgres advisory-lock key: syncs, the cutoff, confirmations and finalization of a day. */
  static final int LOCK_KEY = 4_100_002;

  private final JdbcClient jdbc;
  private final SettingsService settings;

  public DayCloseStore(JdbcClient jdbc, SettingsService settings) {
    this.jdbc = jdbc;
    this.settings = settings;
  }

  /**
   * @param finalizedById null when the system finalized the day
   */
  public record Closed(
      String id,
      Instant finalizedAt,
      @Nullable String finalizedById,
      @Nullable String finalizedByName,
      boolean autoFinalized) {}

  public @Nullable Closed closed(String factoryId, LocalDate date) {
    return jdbc.sql(
            """
            SELECT d.id, d."finalizedAt", d."finalizedById", e.name AS "finalizedByName",
                   d."autoFinalized"
            FROM "DayClose" d LEFT JOIN "Employee" e ON e.id = d."finalizedById"
            WHERE d."factoryId" = :factoryId AND d.date = :date AND d.session = 'LUNCH'
              AND d.status = 'FINALIZED'
            """)
        .param("factoryId", factoryId)
        .param("date", date)
        .query(
            (rs, i) ->
                new Closed(
                    rs.getString("id"),
                    SqlTime.instant(rs, "finalizedAt"),
                    rs.getString("finalizedById"),
                    rs.getString("finalizedByName"),
                    rs.getBoolean("autoFinalized")))
        .optional()
        .orElse(null);
  }

  public boolean isFinalized(String factoryId, LocalDate date) {
    return closed(factoryId, date) != null;
  }

  /** BR-43: when the system finalizes the day if the HC admin has not (product owner). */
  public Instant autoFinalizeAt(String factoryId, LocalDate date) {
    return DayCloseRules.autoFinalizeAt(
        date,
        settings.getTime(factoryId, SettingKey.CLOSE_AUTO_FINALIZE_AT),
        settings.getTime(factoryId, SettingKey.CLOSE_TIME),
        settings.getTime(factoryId, SettingKey.DAY_LOCK_TIME));
  }

  public Instant finalizeOpensAt(String factoryId, LocalDate date) {
    return DayCloseRules.finalizeOpensAt(
        date,
        settings.getTime(factoryId, SettingKey.CLOSE_TIME),
        settings.getTime(factoryId, SettingKey.DAY_LOCK_TIME));
  }

  public LocalTime adjustmentDeadline(String factoryId) {
    return settings.getTime(factoryId, SettingKey.CLOSE_ADJUSTMENT_DEADLINE);
  }

  /**
   * BR-21 / BR-24 / BR-25: what an admin may do for an employee's day, closed at finalization or at
   * the auto-finalization time.
   */
  public AdminDayWindow adminWindow(String factoryId, LocalDate date, Instant now) {
    return RegistrationRules.adminDayWindow(
        now,
        date,
        settings.getTime(factoryId, SettingKey.DAY_LOCK_TIME),
        isFinalized(factoryId, date),
        autoFinalizeAt(factoryId, date));
  }

  /** The error of a change refused by the day's window: finalized (BR-43) or locked (BR-24). */
  public ApiException closedError(String factoryId, LocalDate date) {
    boolean finalized = isFinalized(factoryId, date);
    return new ApiException(
        HttpStatus.CONFLICT,
        finalized ? ApiErrorCode.DAY_FINALIZED : ApiErrorCode.DAY_LOCKED,
        finalized ? "Day is finalized; only adjustments remain (BR-46)" : "Day is locked",
        Map.of("date", date.toString()),
        null);
  }

  /** Serializes the day's attendance writes, confirmations and finalization (one transaction). */
  public void lockDay(String factoryId, LocalDate date) {
    jdbc.sql("SELECT pg_advisory_xact_lock(:key, hashtext(:day))")
        .param("key", LOCK_KEY)
        .param("day", factoryId + ":" + date)
        .query()
        .singleRow();
  }
}
