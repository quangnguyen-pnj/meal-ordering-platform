package vn.pnj.meal.rules.close;

import org.jspecify.annotations.Nullable;

/** BR-40 to BR-42: comparing day D's actual count with its forecast, per meal. */
public final class Reconciliation {
  private Reconciliation() {}

  /** BR-40: per meal, Matched when the actual count equals the forecast. */
  public enum MealStatus {
    MATCHED,
    MISMATCHED
  }

  public static MealStatus status(int forecast, int actual) {
    return forecast == actual ? MealStatus.MATCHED : MealStatus.MISMATCHED;
  }

  /**
   * BR-42: warn when a meal's actual count deviates more than {@code
   * reconciliation.dishDeviationPct} percent from its forecast. A meal with no forecast deviates as
   * soon as anyone eats it; exactly the threshold does not warn ("more than").
   */
  public static boolean deviates(int forecast, int actual, double maxDeviationPct) {
    if (forecast <= 0) {
      return actual > 0;
    }
    return Math.abs(actual - forecast) * 100.0 > maxDeviationPct * forecast;
  }

  /**
   * BR-42: the deviation in percent of the forecast, rounded to one decimal; null when there is no
   * forecast to compare with.
   */
  public static @Nullable Double deviationPct(int forecast, int actual) {
    if (forecast <= 0) {
      return null;
    }
    return Math.round((actual - forecast) * 1000.0 / forecast) / 10.0;
  }

  /** BR-46: effective count = final count + the sum of adjustment lines. */
  public static int effective(int finalCount, int adjustmentDelta) {
    return finalCount + adjustmentDelta;
  }
}
