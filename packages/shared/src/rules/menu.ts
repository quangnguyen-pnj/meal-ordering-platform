import type { DateString } from './time';

/**
 * BR-13: how many dishes of a type a serving day usually has. Each dish type (master data) carries
 * its own optional bounds; a type with neither bound is not checked. The seed keeps the plan's mix:
 * Chay 1, Cháo 1, Mặn 2–3. A different mix only warns; it never blocks publishing.
 */
export interface MenuMixRule {
  typeId: string;
  typeName: string;
  minPerDay: number | null;
  maxPerDay: number | null;
}

export interface MenuMixIssue {
  typeId: string;
  typeName: string;
  actual: number;
  min: number | null;
  max: number | null;
}

export interface MenuMixCheck {
  usual: boolean;
  /** Dishes per type id. */
  counts: Record<string, number>;
  issues: MenuMixIssue[];
}

/** BR-13: compares one day's main-dish types (ids) with each type's usual bounds. */
export function checkMenuMix(
  typeIds: readonly string[],
  rules: readonly MenuMixRule[],
): MenuMixCheck {
  const counts: Record<string, number> = {};
  for (const id of typeIds) counts[id] = (counts[id] ?? 0) + 1;
  const issues: MenuMixIssue[] = [];
  for (const rule of rules) {
    if (rule.minPerDay === null && rule.maxPerDay === null) continue;
    const actual = counts[rule.typeId] ?? 0;
    if (
      (rule.minPerDay !== null && actual < rule.minPerDay) ||
      (rule.maxPerDay !== null && actual > rule.maxPerDay)
    ) {
      issues.push({
        typeId: rule.typeId,
        typeName: rule.typeName,
        actual,
        min: rule.minPerDay,
        max: rule.maxPerDay,
      });
    }
  }
  return { usual: issues.length === 0, counts, issues };
}

export type MenuDayGap = 'MAIN' | 'DESSERT';

/**
 * A week can be published only when every serving day has at least one main dish and at least one
 * dessert (product owner, 2026-10-23). Days that do not serve are skipped. The same check guards
 * edits of a published day, so a published day never loses its last main dish or dessert.
 */
export function menuDayGaps(day: {
  isServing: boolean;
  mainCount: number;
  dessertCount: number;
}): MenuDayGap[] {
  if (!day.isServing) return [];
  const gaps: MenuDayGap[] = [];
  if (day.mainCount < 1) gaps.push('MAIN');
  if (day.dessertCount < 1) gaps.push('DESSERT');
  return gaps;
}

/** Every serving day with what it lacks; empty when the week can be published. */
export function menuWeekGaps(
  days: ReadonlyArray<{
    date: DateString;
    isServing: boolean;
    mainCount: number;
    dessertCount: number;
  }>,
): Array<{ date: DateString; gaps: MenuDayGap[] }> {
  return days.map((d) => ({ date: d.date, gaps: menuDayGaps(d) })).filter((d) => d.gaps.length > 0);
}
