package vn.pnj.meal.rules.menu;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** Weekly menu rules (BR-08, BR-10 to BR-14) and the publishing rule of 2026-10-23. */
public final class MenuRules {
  private static final Locale VI = Locale.forLanguageTag("vi");

  /** JavaScript's {@code \s}: Unicode white space plus the byte-order mark. */
  private static final Pattern SPACES = Pattern.compile("(?U)[\\s\\uFEFF]+");

  private MenuRules() {}

  /**
   * BR-12: a meal is shown as its type in brackets, then the dish name, e.g. "[Chay] Gạo lứt muối
   * mè". The brackets are literal (product owner, 2026-10-21); screens also draw the type's colour.
   */
  public static String mealDisplayName(String typeName, String dishName) {
    return "[" + typeName.strip() + "] " + dishName.strip();
  }

  /** Library names are stored trimmed with single spaces, so "Cá kho " and "Cá kho" are one. */
  public static String normalizeLibraryName(String name) {
    // Every kind of white space becomes one plain space first, so strip() removes NBSP too.
    return SPACES.matcher(Normalizer.normalize(name, Normalizer.Form.NFC)).replaceAll(" ").strip();
  }

  /** Case-insensitive key that decides whether two library entries are the same (no duplicates). */
  public static String libraryNameKey(String name) {
    return normalizeLibraryName(name).toLowerCase(VI);
  }

  /**
   * BR-13: how many dishes of a type a serving day usually has. A type with neither bound is not
   * checked. The seed keeps the plan's mix: Chay 1, Cháo 1, Mặn 2–3.
   */
  public record MixRule(String typeId, String typeName, Integer minPerDay, Integer maxPerDay) {}

  public record MixIssue(String typeId, String typeName, int actual, Integer min, Integer max) {}

  /**
   * @param counts dishes per type id, in the order the types first appear
   */
  public record MixCheck(boolean usual, Map<String, Integer> counts, List<MixIssue> issues) {}

  /** BR-13: compares one day's main-dish types (ids) with each type's usual bounds. Warns only. */
  public static MixCheck checkMenuMix(List<String> typeIds, List<MixRule> rules) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String id : typeIds) {
      counts.merge(id, 1, Integer::sum);
    }
    List<MixIssue> issues = new ArrayList<>();
    for (MixRule rule : rules) {
      if (rule.minPerDay() == null && rule.maxPerDay() == null) {
        continue;
      }
      int actual = counts.getOrDefault(rule.typeId(), 0);
      if ((rule.minPerDay() != null && actual < rule.minPerDay())
          || (rule.maxPerDay() != null && actual > rule.maxPerDay())) {
        issues.add(
            new MixIssue(
                rule.typeId(), rule.typeName(), actual, rule.minPerDay(), rule.maxPerDay()));
      }
    }
    return new MixCheck(issues.isEmpty(), counts, List.copyOf(issues));
  }

  public enum DayGap {
    MAIN,
    DESSERT
  }

  /**
   * A week can be published only when every serving day has at least one main dish and one dessert
   * (product owner, 2026-10-23). Days that do not serve are skipped. The same check guards edits of
   * a published day, so a published day never loses its last main dish or dessert.
   */
  public static List<DayGap> menuDayGaps(boolean isServing, int mainCount, int dessertCount) {
    if (!isServing) {
      return List.of();
    }
    List<DayGap> gaps = new ArrayList<>();
    if (mainCount < 1) {
      gaps.add(DayGap.MAIN);
    }
    if (dessertCount < 1) {
      gaps.add(DayGap.DESSERT);
    }
    return List.copyOf(gaps);
  }

  public record DayCounts(LocalDate date, boolean isServing, int mainCount, int dessertCount) {}

  public record DayGaps(LocalDate date, List<DayGap> gaps) {}

  /** Every serving day with what it lacks; empty when the week can be published. */
  public static List<DayGaps> menuWeekGaps(Collection<DayCounts> days) {
    return days.stream()
        .map(
            d -> new DayGaps(d.date(), menuDayGaps(d.isServing(), d.mainCount(), d.dessertCount())))
        .filter(d -> !d.gaps().isEmpty())
        .toList();
  }

  /**
   * BR-08: the days a menu week offers. Monday–Sunday are always listed so the HC admin can turn
   * Saturday or Sunday on per week (product owner, 2026-10-23). Which days serve by default comes
   * from the {@code servingDays} setting ({@link #isDefaultServingDay}).
   */
  public static List<LocalDate> menuWeekDates(LocalDate weekStart) {
    if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
      throw new IllegalArgumentException("weekStart must be a Monday, got " + weekStart);
    }
    return IntStream.range(0, 7).mapToObj(weekStart::plusDays).toList();
  }

  /** BR-08: a day serves by default when its ISO weekday is in the {@code servingDays} setting. */
  public static boolean isDefaultServingDay(LocalDate date, Collection<Integer> servingDays) {
    return servingDays.contains(date.getDayOfWeek().getValue());
  }

  public record ItemRef(String id, String dishId) {}

  /**
   * @param id existing item id; null for a new item
   */
  public record DesiredItem(String id, String dishId) {}

  public enum EditErrorCode {
    UNKNOWN_ITEM,
    DUPLICATE_ITEM,
    DUPLICATE_DISH
  }

  /**
   * @param ref the item id for item errors, the dish id for {@code DUPLICATE_DISH}
   */
  public record EditError(EditErrorCode code, String ref) {}

  public record Keep(String id, int sortOrder) {}

  /** BR-14: the dish of an existing item changes; its registrations move to the replacement. */
  public record Replace(String fromId, String fromDishId, String toDishId, int sortOrder) {}

  /** BR-14: the item is removed; its registrations need reselection. */
  public record Remove(String id, String dishId) {}

  public record Add(String dishId, int sortOrder) {}

  public record EditPlan(
      List<Keep> keep, List<Replace> replace, List<Remove> remove, List<Add> add) {}

  /** Either a plan or the reasons there is none. */
  public record EditResult(EditPlan plan, List<EditError> errors) {
    public boolean ok() {
      return errors.isEmpty();
    }
  }

  /**
   * BR-11 / BR-14: turns the saved list of a day's main dishes into keep / replace / remove / add
   * steps. An entry that carries an existing item id with a different dish is a replacement; an
   * existing item missing from the list is a removal. The same dish cannot appear twice on a day.
   */
  public static EditResult planMenuDayEdit(List<ItemRef> existing, List<DesiredItem> desired) {
    Map<String, ItemRef> byId = new HashMap<>();
    existing.forEach(i -> byId.put(i.id(), i));
    List<EditError> errors = new ArrayList<>();
    Set<String> seenIds = new HashSet<>();
    Set<String> seenDishes = new HashSet<>();
    for (DesiredItem item : desired) {
      if (item.id() != null) {
        if (!byId.containsKey(item.id())) {
          errors.add(new EditError(EditErrorCode.UNKNOWN_ITEM, item.id()));
        } else if (seenIds.contains(item.id())) {
          errors.add(new EditError(EditErrorCode.DUPLICATE_ITEM, item.id()));
        }
        seenIds.add(item.id());
      }
      if (!seenDishes.add(item.dishId())) {
        errors.add(new EditError(EditErrorCode.DUPLICATE_DISH, item.dishId()));
      }
    }
    if (!errors.isEmpty()) {
      return new EditResult(null, List.copyOf(errors));
    }

    List<Keep> keep = new ArrayList<>();
    List<Replace> replace = new ArrayList<>();
    List<Remove> remove = new ArrayList<>();
    List<Add> add = new ArrayList<>();
    for (int sortOrder = 0; sortOrder < desired.size(); sortOrder++) {
      DesiredItem item = desired.get(sortOrder);
      ItemRef current = item.id() == null ? null : byId.get(item.id());
      if (current == null) {
        add.add(new Add(item.dishId(), sortOrder));
      } else if (current.dishId().equals(item.dishId())) {
        keep.add(new Keep(current.id(), sortOrder));
      } else {
        replace.add(new Replace(current.id(), current.dishId(), item.dishId(), sortOrder));
      }
    }
    for (ItemRef item : existing) {
      if (!seenIds.contains(item.id())) {
        remove.add(new Remove(item.id(), item.dishId()));
      }
    }
    return new EditResult(
        new EditPlan(
            List.copyOf(keep), List.copyOf(replace), List.copyOf(remove), List.copyOf(add)),
        List.of());
  }
}
