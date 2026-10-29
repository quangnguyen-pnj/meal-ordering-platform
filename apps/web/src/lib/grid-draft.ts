import type { RegistrationGridView, SelectionLine } from '@/lib/api-types';

/** Cell text by employee code, then menu item id. Empty means 0, like a blank paper cell. */
export type GridValues = Record<string, Record<string, string>>;

export function gridValues(view: RegistrationGridView, date: string): GridValues {
  const day = view.days.find((d) => d.date === date);
  const out: GridValues = {};
  for (const row of view.rows) {
    const cell = row.cells[date];
    out[row.employeeCode] = Object.fromEntries(
      (day?.items ?? []).map((i) => {
        const q = cell?.quantities[i.id] ?? 0;
        return [i.id, q > 0 ? String(q) : ''];
      }),
    );
  }
  return out;
}

/** BR-20 in the grid: a blank or a whole number from 0; anything else is invalid (null). */
export function parseCell(text: string): number | null {
  const v = text.trim();
  if (v === '') return 0;
  return /^\d+$/.test(v) ? Number(v) : null;
}

export interface GridChanges {
  /** Changed rows with their complete selection for the day (the API replaces the day). */
  rows: Array<{ employeeCode: string; lines: SelectionLine[] }>;
  /** Invalid cells by employee code (menu item ids). */
  invalid: Record<string, string[]>;
}

export function gridChanges(
  view: RegistrationGridView,
  date: string,
  values: GridValues,
): GridChanges {
  const day = view.days.find((d) => d.date === date);
  const result: GridChanges = { rows: [], invalid: {} };
  if (!day) return result;
  for (const row of view.rows) {
    const stored = row.cells[date]?.quantities ?? {};
    const lines: SelectionLine[] = [];
    let changed = false;
    for (const item of day.items) {
      const q = parseCell(values[row.employeeCode]?.[item.id] ?? '');
      if (q === null) {
        (result.invalid[row.employeeCode] ??= []).push(item.id);
        continue;
      }
      if (q !== (stored[item.id] ?? 0)) changed = true;
      if (q > 0) lines.push({ menuItemId: item.id, quantity: q });
    }
    if (changed && !result.invalid[row.employeeCode]) {
      result.rows.push({ employeeCode: row.employeeCode, lines });
    }
  }
  return result;
}

/** Column totals of the current values, ignoring invalid cells. */
export function columnTotals(
  view: RegistrationGridView,
  date: string,
  values: GridValues,
): Record<string, number> {
  const day = view.days.find((d) => d.date === date);
  const totals: Record<string, number> = {};
  for (const item of day?.items ?? []) {
    totals[item.id] = view.rows.reduce(
      (s, r) => s + (parseCell(values[r.employeeCode]?.[item.id] ?? '') ?? 0),
      0,
    );
  }
  return totals;
}

/** Row filters of the grid toolbar, besides the name/code search. */
export const GRID_FILTERS = ['all', 'empty', 'changed', 'errors', 'needsReselect'] as const;
export type GridFilter = (typeof GRID_FILTERS)[number];

/**
 * Employee codes in each filter for the current values: "empty" has no meal in the cells (left
 * out when someone has left or a cell is invalid), "changed" is what "Lưu ngày" would send,
 * "errors" has an invalid cell or an error from the last save.
 */
export function gridFilterGroups(
  view: RegistrationGridView,
  date: string,
  values: GridValues,
  changes: GridChanges,
  rowErrors: Record<string, string[]>,
): Record<GridFilter, string[]> {
  const day = view.days.find((d) => d.date === date);
  const changed = new Set(changes.rows.map((r) => r.employeeCode));
  const out: Record<GridFilter, string[]> = {
    all: [],
    empty: [],
    changed: [],
    errors: [],
    needsReselect: [],
  };
  for (const row of view.rows) {
    const code = row.employeeCode;
    const cell = row.cells[date];
    const invalid = !!changes.invalid[code];
    out.all.push(code);
    if (changed.has(code)) out.changed.push(code);
    if (invalid || (rowErrors[code]?.length ?? 0) > 0) out.errors.push(code);
    if ((cell?.needsReselect ?? 0) > 0) out.needsReselect.push(code);
    const meals = (day?.items ?? []).reduce(
      (s, i) => s + (parseCell(values[code]?.[i.id] ?? '') ?? 0),
      0,
    );
    if (!cell?.blocked && !invalid && meals === 0) out.empty.push(code);
  }
  return out;
}

/** Lower case without Vietnamese accents, so "nguyen" finds "Nguyễn" and "dung" finds "Dũng". */
export function searchKey(text: string): string {
  return text
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .replace(/[đĐ]/g, 'd')
    .toLocaleLowerCase('vi')
    .trim();
}

/** The search box matches the name or the employee code; `term` comes from `searchKey`. */
export function matchesGridSearch(row: { name: string; employeeCode: string }, term: string) {
  return !term || searchKey(row.name).includes(term) || searchKey(row.employeeCode).includes(term);
}
