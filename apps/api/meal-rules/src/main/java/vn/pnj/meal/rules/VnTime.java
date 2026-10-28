package vn.pnj.meal.rules;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;

/**
 * Asia/Ho_Chi_Minh helpers. Instants are stored in UTC (plan 7.1 rule 4); these functions convert
 * to and from Vietnam wall time. None of them reads the clock: callers pass {@code now} in.
 *
 * <p>Vietnam has used UTC+7 without DST since 1975, so the zone and a fixed offset agree; the zone
 * id is used because java.time handles it exactly.
 */
public final class VnTime {
  public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);

  private VnTime() {}

  /** Parses a serving day {@code YYYY-MM-DD}; impossible dates such as 2026-02-30 are rejected. */
  public static LocalDate parseDate(String date) {
    try {
      return LocalDate.parse(date, DATE);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Invalid date \"" + date + "\", expected YYYY-MM-DD", e);
    }
  }

  /** Parses a time of day {@code HH:mm} (00:00 to 23:59), as stored in settings. */
  public static LocalTime parseTimeOfDay(String time) {
    try {
      return LocalTime.parse(time, TIME);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Invalid time \"" + time + "\", expected HH:mm", e);
    }
  }

  /** Vietnam wall time of a UTC instant. */
  public static LocalDateTime toVn(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZONE);
  }

  /** Vietnam calendar date of a UTC instant. */
  public static LocalDate vnDateOf(Instant instant) {
    return toVn(instant).toLocalDate();
  }

  /** The UTC instant at which Vietnam wall time reads {@code date} + {@code time}. */
  public static Instant vnToUtc(LocalDate date, LocalTime time) {
    try {
      return date.atTime(time).atZone(ZONE).toInstant();
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("Cannot place " + date + " " + time + " in Vietnam", e);
    }
  }

  /** Monday of the week containing {@code date}. */
  public static LocalDate mondayOf(LocalDate date) {
    return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }

  /**
   * ISO 8601 week of a date: weeks start on Monday and week 1 holds the year's first Thursday, so
   * 29/12 can be week 1 of the next year. Shown as "Tuần 43" next to a week range.
   */
  public static IsoWeek isoWeekOf(LocalDate date) {
    return new IsoWeek(
        date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
  }

  public record IsoWeek(int year, int week) {}
}
