package vn.pnj.meal.api.me;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import vn.pnj.meal.api.access.RequestPrincipal;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.db.SqlTime;
import vn.pnj.meal.api.settings.SettingKey;
import vn.pnj.meal.api.settings.SettingsService;
import vn.pnj.meal.rules.deadline.Deadlines;
import vn.pnj.meal.rules.deadline.WeekdayTime;

/** The signed-in user's banners and in-app notices; data is always filtered to the caller. */
@Service
public class MeService {
  private static final int MAX_NOTIFICATION_BANNERS = 5;

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final SettingsService settings;
  private final ClockService clock;

  public MeService(JdbcClient jdbc, JsonMapper json, SettingsService settings, ClockService clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.settings = settings;
    this.clock = clock;
  }

  /**
   * @param kind message key in the web messages file: MENU_NOT_PUBLISHED or NOTIFICATION
   * @param notificationId NOTIFICATION banners only: the notification to mark read
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record BannerView(
      String id,
      @Schema(allowableValues = {"info", "warning", "error"}) String severity,
      @Schema(allowableValues = {"MENU_NOT_PUBLISHED", "NOTIFICATION"}) String kind,
      Object params,
      @Nullable String notificationId,
      @Nullable String notificationType) {}

  /**
   * Banners for the shared layout (plan section 4): BR-06 "menu not published" for HC admins,
   * computed on every request, and the caller's unread in-app notices (BR-14).
   */
  @Transactional(readOnly = true)
  public List<BannerView> banners(RequestPrincipal p) {
    List<BannerView> banners = new ArrayList<>();
    if (p.hcFactoryIds().contains(p.factoryId())) {
      banners.addAll(menuWarnings(p.factoryId()));
    }
    jdbc.sql(
            """
            SELECT id, type::text, payload::text FROM "Notification"
            WHERE "employeeId" = :employeeId AND "readAt" IS NULL
            ORDER BY "createdAt" DESC, id DESC LIMIT :limit
            """)
        .param("employeeId", p.employeeId())
        .param("limit", MAX_NOTIFICATION_BANNERS)
        .query(
            (rs, i) ->
                new BannerView(
                    "notification:" + rs.getString("id"),
                    "info",
                    "NOTIFICATION",
                    json.readTree(rs.getString("payload")),
                    rs.getString("id"),
                    rs.getString("type")))
        .list()
        .forEach(banners::add);
    return banners;
  }

  private record CanteenWeek(String id, String name, boolean published) {}

  /** BR-06: one warning per active canteen whose next-week menu is still unpublished. */
  public List<BannerView> menuWarnings(String factoryId) {
    Instant now = clock.now();
    LocalDate weekStart = Deadlines.upcomingWeekStart(now);
    WeekdayTime warnRule = settings.getWeekdayTime(factoryId, SettingKey.MENU_PUBLISH_WARN_AT);
    return jdbc
        .sql(
            """
            SELECT c.id, c.name, EXISTS (
                     SELECT 1 FROM "MenuWeek" w WHERE w."canteenId" = c.id
                       AND w."weekStart" = :weekStart AND w.status <> 'DRAFT') AS published
            FROM "Canteen" c WHERE c."factoryId" = :factoryId AND c.active ORDER BY c.code
            """)
        .param("weekStart", weekStart)
        .param("factoryId", factoryId)
        .query(CanteenWeek.class)
        .list()
        .stream()
        .filter(c -> Deadlines.isMenuPublishWarningDue(now, weekStart, warnRule, c.published()))
        .map(
            c -> {
              Map<String, Object> params = new LinkedHashMap<>();
              params.put("canteen", c.name());
              params.put("weekStart", weekStart.toString());
              return new BannerView(
                  "menu-not-published:" + c.id() + ":" + weekStart,
                  "warning",
                  "MENU_NOT_PUBLISHED",
                  params,
                  null,
                  null);
            })
        .toList();
  }

  public record Read(String id) {}

  private record NotificationRow(String employeeId, boolean read) {}

  @Transactional
  public Read markRead(String employeeId, String notificationId) {
    NotificationRow row =
        jdbc.sql(
                """
                SELECT "employeeId", "readAt" IS NOT NULL AS read FROM "Notification" WHERE id = :id
                """)
            .param("id", notificationId)
            .query(NotificationRow.class)
            .optional()
            // Someone else's notice looks the same as a missing one.
            .filter(n -> n.employeeId().equals(employeeId))
            .orElseThrow(() -> ApiException.notFound("Notification not found"));
    if (!row.read()) {
      jdbc.sql("UPDATE \"Notification\" SET \"readAt\" = :now WHERE id = :id")
          .param("now", SqlTime.utc(clock.now()))
          .param("id", notificationId)
          .update();
    }
    return new Read(notificationId);
  }
}
