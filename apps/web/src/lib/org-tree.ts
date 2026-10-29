import type { OrgUnitView } from '@/lib/api-types';

/**
 * Read-only views of the org tree for /admin/org. The rules themselves (BR-60 nearest meal unit,
 * BR-62 inheritance) are resolved by the api; these helpers only count and walk its answer.
 */
export interface OrgTreeIndex {
  byId: Map<string, OrgUnitView>;
  children: Map<string | null, OrgUnitView[]>;
}

export function indexTree(nodes: readonly OrgUnitView[]): OrgTreeIndex {
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const children = new Map<string | null, OrgUnitView[]>();
  for (const n of nodes) children.set(n.parentId, [...(children.get(n.parentId) ?? []), n]);
  return { byId, children };
}

/** The node and everything below it, depth first. */
export function* subtree(nodeId: string, idx: OrgTreeIndex): Generator<OrgUnitView> {
  const stack = [idx.byId.get(nodeId)].filter((n): n is OrgUnitView => n !== undefined);
  while (stack.length > 0) {
    const n = stack.pop()!;
    yield n;
    stack.push(...(idx.children.get(n.id) ?? []).slice().reverse());
  }
}

/** "Khối Sản xuất / Xưởng Chế tác / Tổ Nhẫn" (BR-61). */
export function nodePath(nodeId: string, idx: OrgTreeIndex): string {
  const names: string[] = [];
  for (let n = idx.byId.get(nodeId); n; n = n.parentId ? idx.byId.get(n.parentId) : undefined) {
    names.unshift(n.name);
  }
  return names.join(' / ');
}

/** Active employees per meal unit id; the null key counts employees with no meal unit. */
export function headcountByMealUnit(nodes: Iterable<OrgUnitView>): Map<string | null, number> {
  const counts = new Map<string | null, number>();
  for (const n of nodes) {
    if (n.directHeadcount > 0) {
      counts.set(n.mealUnitId, (counts.get(n.mealUnitId) ?? 0) + n.directHeadcount);
    }
  }
  return counts;
}

/** Per node: employees at or below it who have no meal unit, for the tree warnings. */
export function unassignedBelow(idx: OrgTreeIndex): Map<string, number> {
  const counts = new Map<string, number>();
  for (const n of idx.byId.values()) {
    if (n.mealUnitId !== null || n.directHeadcount === 0) continue;
    for (let cur: OrgUnitView | undefined = n; cur;) {
      counts.set(cur.id, (counts.get(cur.id) ?? 0) + n.directHeadcount);
      cur = cur.parentId ? idx.byId.get(cur.parentId) : undefined;
    }
  }
  return counts;
}

/**
 * Meal units at or below `nodeId` that would take `field` from it: nothing between them and the
 * node sets its own value (BR-62). Floor and canteen only matter at meal units (BR-63), so a
 * value set on a node with none of these has no effect.
 */
export function mealUnitsInheriting(
  nodeId: string,
  field: 'floorId' | 'canteenId',
  idx: OrgTreeIndex,
): OrgUnitView[] {
  const found: OrgUnitView[] = [];
  const root = idx.byId.get(nodeId);
  if (!root) return found;
  const stack = [root];
  while (stack.length > 0) {
    const n = stack.pop()!;
    if (n !== root && n.own[field] !== null) continue;
    if (n.isMealUnit) found.push(n);
    stack.push(...(idx.children.get(n.id) ?? []).slice().reverse());
  }
  return found;
}

export interface InheritedDeptAdmin {
  assignmentId: string;
  employeeCode: string;
  name: string;
  /** The ancestor the admin is assigned to. */
  sourceName: string;
}

/**
 * Dept admins of the ancestors: an assignment covers its node and everything below it, and adds
 * to (never replaces) admins assigned lower down. Assignments on an inactive node grant nothing,
 * and someone also assigned at the node itself is listed there only.
 */
export function inheritedDeptAdmins(nodeId: string, idx: OrgTreeIndex): InheritedDeptAdmin[] {
  const node = idx.byId.get(nodeId);
  if (!node) return [];
  const seen = new Set(node.deptAdmins.map((a) => a.employeeCode));
  const found: InheritedDeptAdmin[] = [];
  for (
    let cur = node.parentId ? idx.byId.get(node.parentId) : undefined;
    cur;
    cur = cur.parentId ? idx.byId.get(cur.parentId) : undefined
  ) {
    if (!cur.active) continue;
    for (const a of cur.deptAdmins) {
      if (seen.has(a.employeeCode)) continue;
      seen.add(a.employeeCode);
      found.push({ ...a, sourceName: cur.name });
    }
  }
  return found;
}
