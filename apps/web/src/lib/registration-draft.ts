import type { DishTypeLabel, MyDayView, MyWeekSave, SelectionLine } from '@/lib/api-types';

/** Quantities the employee is editing: date → menu item → quantity. */
export type Draft = Record<string, Record<string, number>>;

export function draftFromDays(days: readonly MyDayView[]): Draft {
  return Object.fromEntries(
    days.map((d) => [d.date, Object.fromEntries(d.items.map((i) => [i.menuItemId, i.quantity]))]),
  );
}

export const isEditable = (day: MyDayView) => day.window !== 'LOCKED';

export function linesOf(quantities: Record<string, number> | undefined): SelectionLine[] {
  return Object.entries(quantities ?? {})
    .filter(([, quantity]) => quantity > 0)
    .map(([menuItemId, quantity]) => ({ menuItemId, quantity }));
}

const total = (q: Record<string, number> | undefined) =>
  Object.values(q ?? {}).reduce((s, n) => s + (n > 0 ? n : 0), 0);

export function dayTotal(draft: Draft, date: string): number {
  return total(draft[date]);
}

/** Editable days whose draft differs from what is stored. */
export function changedDates(days: readonly MyDayView[], draft: Draft): string[] {
  return days
    .filter(isEditable)
    .filter((d) => d.items.some((i) => (draft[d.date]?.[i.menuItemId] ?? 0) !== i.quantity))
    .map((d) => d.date);
}

/** The body of PUT /registrations/me/week: only the changed, editable days (BR-22, BR-23). */
export function saveBody(weekStart: string, days: readonly MyDayView[], draft: Draft): MyWeekSave {
  return {
    weekStart,
    days: changedDates(days, draft).map((date) => ({ date, lines: linesOf(draft[date]) })),
  };
}

export function setQuantity(draft: Draft, date: string, itemId: string, quantity: number): Draft {
  return { ...draft, [date]: { ...draft[date], [itemId]: Math.max(0, Math.floor(quantity)) } };
}

export interface ForecastRow {
  displayName: string;
  type: DishTypeLabel;
  /** date → forecast quantity; days without the dish are missing. */
  byDate: Record<string, number>;
  total: number;
}

export interface ForecastGrid {
  rows: ForecastRow[];
  /** date → forecast on dishes no longer on the day's menu (BR-14 after the snapshot). */
  other: Record<string, number>;
  /** date → the day's forecast total (BR-07). */
  dayTotals: Record<string, number>;
  total: number;
}

/**
 * BR-07: the week's forecast for review, one row per dish with a positive forecast. A dish served
 * on several days shares one row (same display name). Forecast lines on a dish replaced or removed
 * after the snapshot are not among the day's items, so they go to `other` and the day totals
 * still match the snapshot.
 */
export function forecastGrid(days: readonly MyDayView[]): ForecastGrid {
  const rows = new Map<string, ForecastRow>();
  const other: Record<string, number> = {};
  const dayTotals: Record<string, number> = {};
  for (const day of days) {
    let listed = 0;
    for (const item of day.items) {
      if (item.forecast <= 0) continue;
      let row = rows.get(item.displayName);
      if (!row) {
        row = { displayName: item.displayName, type: item.type, byDate: {}, total: 0 };
        rows.set(item.displayName, row);
      }
      row.byDate[day.date] = (row.byDate[day.date] ?? 0) + item.forecast;
      row.total += item.forecast;
      listed += item.forecast;
    }
    const dayTotal = day.forecastTotal ?? listed;
    dayTotals[day.date] = dayTotal;
    if (dayTotal > listed) other[day.date] = dayTotal - listed;
  }
  return {
    rows: [...rows.values()],
    other,
    dayTotals,
    total: Object.values(dayTotals).reduce((s, n) => s + n, 0),
  };
}
