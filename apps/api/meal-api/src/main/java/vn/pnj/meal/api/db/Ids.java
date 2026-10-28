package vn.pnj.meal.api.db;

import java.security.SecureRandom;

/**
 * New row ids: text like the cuid ids of the legacy rows ("c" + time + random, base 36), roughly
 * ordered by creation and unguessable.
 */
public final class Ids {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final char[] BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

  private Ids() {}

  public static String newId(long epochMillis) {
    StringBuilder id = new StringBuilder("c").append(Long.toString(epochMillis, 36));
    for (int i = 0; i < 16; i++) {
      id.append(BASE36[RANDOM.nextInt(BASE36.length)]);
    }
    return id.toString();
  }
}
