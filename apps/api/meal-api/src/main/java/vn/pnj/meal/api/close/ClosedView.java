package vn.pnj.meal.api.close;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * BR-43: how a day was finalized, for admin screens.
 *
 * @param finalizedByName the HC admin; null when the system finalized the day at {@code
 *     close.autoFinalizeAt}
 */
public record ClosedView(
    Instant finalizedAt, @Nullable String finalizedByName, boolean autoFinalized) {

  public static @Nullable ClosedView of(DayCloseStore.@Nullable Closed closed) {
    return closed == null
        ? null
        : new ClosedView(closed.finalizedAt(), closed.finalizedByName(), closed.autoFinalized());
  }
}
