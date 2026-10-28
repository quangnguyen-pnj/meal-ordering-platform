package vn.pnj.meal.api.common;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Instants on the wire look like JavaScript's {@code toISOString()}: always milliseconds and Z. */
public final class Times {
  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private Times() {}

  public static String iso(Instant instant) {
    return instant == null ? null : ISO_MILLIS.format(instant);
  }
}
