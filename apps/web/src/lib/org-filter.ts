import type { OrgFilterOption } from '@/lib/api-types';

/** Khối, then Trung tâm / Phòng ban, then Bộ phận: deeper levels are reached through search. */
export const ORG_FILTER_DEPTH = 3;

export interface OrgFilterLevel {
  /** DMP level name most nodes of this select carry, e.g. "Khối". */
  label: string;
  options: OrgFilterOption[];
  /** Selected id at this level, or '' for "all". */
  value: string;
}

/**
 * Cascading selects for the Khối / Trung tâm / Phòng ban filter (2026-10-23). The first select
 * holds the top nodes in scope; a single top node with children (the company) is skipped. Each
 * choice opens a select of its children. The filter value is the deepest chosen node.
 */
export function orgFilterLevels(
  options: readonly OrgFilterOption[],
  selectedId: string | null,
): OrgFilterLevel[] {
  const byId = new Map(options.map((o) => [o.id, o]));
  const children = new Map<string | null, OrgFilterOption[]>();
  for (const o of options) {
    const parent = o.parentId !== null && byId.has(o.parentId) ? o.parentId : null;
    children.set(parent, [...(children.get(parent) ?? []), o]);
  }
  let top = children.get(null) ?? [];
  let skipped: string | null = null;
  if (top.length === 1 && (children.get(top[0]!.id) ?? []).length > 0) {
    skipped = top[0]!.id;
    top = children.get(skipped) ?? [];
  }

  const chain: string[] = [];
  for (let id = selectedId; id !== null && id !== skipped && byId.has(id);) {
    chain.unshift(id);
    id = byId.get(id)!.parentId;
  }

  const levels: OrgFilterLevel[] = [];
  let current = top;
  for (let depth = 0; depth < ORG_FILTER_DEPTH && current.length > 0; depth++) {
    const value = chain[depth] ?? '';
    levels.push({ label: commonLevelName(current), options: current, value });
    if (!value) break;
    current = children.get(value) ?? [];
  }
  return levels;
}

/** The value after changing the select at `depth`; clearing it falls back to its parent's. */
export function orgFilterChange(levels: readonly OrgFilterLevel[], depth: number, id: string) {
  return id || (depth > 0 ? levels[depth - 1]!.value : '');
}

function commonLevelName(nodes: readonly OrgFilterOption[]): string {
  const counts = new Map<string, number>();
  for (const n of nodes) counts.set(n.levelName, (counts.get(n.levelName) ?? 0) + 1);
  return [...counts.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] ?? '';
}
