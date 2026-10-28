import type { Prisma, PrismaClient } from '@prisma/client';
import {
  indexOrgNodes,
  resolveEmployeeMealAssignment,
  resolveMealUnitId,
  type EmployeeMappingView,
  type ListScope,
  type OrgFilterOption,
  type OrgNodeIndex,
} from '@meal/shared';

type Db = PrismaClient | Prisma.TransactionClient;

export interface OrgSnapshot {
  nodes: Array<{
    id: string;
    dmpCode: string;
    name: string;
    level: number;
    dmpLevelName: string;
    parentId: string | null;
    path: string;
    isMealUnit: boolean;
    floorId: string | null;
    canteenId: string | null;
    costCenterId: string | null;
    active: boolean;
  }>;
  byId: Map<string, OrgSnapshot['nodes'][number]>;
  index: OrgNodeIndex;
}

/** The whole org tree of a factory, indexed for the shared mapping rules (BR-60, BR-62, BR-63). */
export async function loadOrg(db: Db, factoryId: string): Promise<OrgSnapshot> {
  const nodes = await db.orgUnit.findMany({
    where: { factoryId },
    select: {
      id: true,
      dmpCode: true,
      name: true,
      level: true,
      dmpLevelName: true,
      parentId: true,
      path: true,
      isMealUnit: true,
      floorId: true,
      canteenId: true,
      costCenterId: true,
      active: true,
    },
    orderBy: [{ level: 'asc' }, { name: 'asc' }],
  });
  return { nodes, byId: new Map(nodes.map((n) => [n.id, n])), index: indexOrgNodes(nodes) };
}

/** BR-61: screens show the full path, e.g. "Khối Sản xuất / Xưởng Chế tác / Chuyền A". */
export function orgPathName(org: OrgSnapshot, nodeId: string): string {
  const node = org.byId.get(nodeId);
  if (!node) return '';
  return node.path
    .split('/')
    .filter(Boolean)
    .map((id) => org.byId.get(id))
    .filter((n) => n !== undefined && n.level > 1)
    .map((n) => n!.name)
    .join(' / ');
}

/**
 * BR-60: recomputes Employee.mealUnitId for every employee of the factory whose nearest flagged
 * node changed (after a flag change, an org sync or a tree move). Returns how many changed.
 */
export async function recomputeMealUnits(db: Db, factoryId: string): Promise<number> {
  const org = await loadOrg(db, factoryId);
  const employees = await db.employee.findMany({
    where: { orgUnit: { factoryId } },
    select: { id: true, orgUnitId: true, mealUnitId: true },
  });
  const moves = new Map<string | null, string[]>();
  for (const e of employees) {
    const target = resolveMealUnitId(e.orgUnitId, org.index);
    if (target === e.mealUnitId) continue;
    const ids = moves.get(target) ?? [];
    ids.push(e.id);
    moves.set(target, ids);
  }
  let changed = 0;
  for (const [mealUnitId, ids] of moves) {
    const res = await db.employee.updateMany({ where: { id: { in: ids } }, data: { mealUnitId } });
    changed += res.count;
  }
  return changed;
}

export interface EmployeeForMapping {
  employeeCode: string;
  name: string;
  status: 'ACTIVE' | 'LEFT';
  orgUnitId: string;
  floorOverrideId: string | null;
  dmpCostCenterCode: string | null;
  roleAssignments: Array<{ role: string }>;
}

export const EMPLOYEE_FOR_MAPPING_SELECT = {
  employeeCode: true,
  name: true,
  status: true,
  orgUnitId: true,
  floorOverrideId: true,
  dmpCostCenterCode: true,
  roleAssignments: { where: { revokedAt: null }, select: { role: true } },
} satisfies Prisma.EmployeeSelect;

/**
 * BR-60 / BR-63 for a list of employees: meal unit, its validity, floor, canteen and cost center.
 * `costCenterIdByCode` turns the DMP-supplied cost-center code into an id (the last fallback).
 */
export function describeEmployees(
  org: OrgSnapshot,
  employees: readonly EmployeeForMapping[],
  costCenterIdByCode: ReadonlyMap<string, string>,
  names: MasterNames,
): EmployeeMappingView[] {
  return employees.map((e) => {
    const a = resolveEmployeeMealAssignment({
      orgUnitId: e.orgUnitId,
      floorOverrideId: e.floorOverrideId,
      dmpCostCenterId: e.dmpCostCenterCode
        ? (costCenterIdByCode.get(e.dmpCostCenterCode) ?? null)
        : null,
      index: org.index,
    });
    return {
      employeeCode: e.employeeCode,
      name: e.name,
      status: e.status,
      orgUnitId: e.orgUnitId,
      orgPath: orgPathName(org, e.orgUnitId),
      mealUnitId: a.mealUnitId,
      mealUnitName: a.mealUnitId ? (org.byId.get(a.mealUnitId)?.name ?? null) : null,
      mealStatus: a.status,
      missing: a.missing,
      floorId: a.floorId,
      floorName: a.floorId ? (names.get(a.floorId) ?? null) : null,
      floorSource: a.floorSource,
      floorOverrideId: e.floorOverrideId,
      canteenId: a.canteenId,
      canteenName: a.canteenId ? (names.get(a.canteenId) ?? null) : null,
      costCenterId: a.costCenterId,
      costCenterName: a.costCenterId ? (names.get(a.costCenterId) ?? null) : null,
      costCenterSource: a.costCenterSource,
      roles: ['EMPLOYEE', ...new Set(e.roleAssignments.map((r) => r.role))],
    };
  });
}

/** Active cost centers by code, for resolving the DMP-supplied code (BR-62). */
export async function costCenterIdsByCode(db: Db, factoryId: string): Promise<Map<string, string>> {
  const rows = await db.costCenter.findMany({
    where: { factoryId, active: true },
    select: { id: true, code: true },
  });
  return new Map(rows.map((r) => [r.code, r.id]));
}

/** Display names of floors, canteens and cost centers by id (ids are unique across the three). */
export type MasterNames = ReadonlyMap<string, string>;

export async function loadMasterNames(db: Db, factoryId: string): Promise<MasterNames> {
  const [floors, canteens, costCenters] = await Promise.all([
    db.floor.findMany({ where: { factoryId }, select: { id: true, name: true } }),
    db.canteen.findMany({ where: { factoryId }, select: { id: true, name: true } }),
    db.costCenter.findMany({ where: { factoryId }, select: { id: true, code: true, name: true } }),
  ]);
  return new Map([
    ...floors.map((f) => [f.id, f.name] as const),
    ...canteens.map((c) => [c.id, c.name] as const),
    ...costCenters.map((c) => [c.id, `${c.code} · ${c.name}`] as const),
  ]);
}

/**
 * Org nodes a caller may filter by (Khối / Trung tâm / Phòng ban filters, 2026-10-23): active
 * nodes inside the caller's list scope, in tree order. A dept admin sees only their own subtree.
 */
export function orgFilterOptions(org: OrgSnapshot, scope: ListScope): OrgFilterOption[] {
  const inScope = (path: string) => {
    switch (scope.kind) {
      case 'all':
      case 'factories':
        return true;
      case 'paths':
        return scope.paths.some((p) => path.startsWith(p));
      default:
        return false;
    }
  };
  return org.nodes
    .filter((n) => n.active && inScope(n.path))
    .sort((a, b) => a.level - b.level || a.name.localeCompare(b.name, 'vi'))
    .map((n) => ({
      id: n.id,
      name: n.name,
      parentId: n.parentId,
      level: n.level,
      levelName: n.dmpLevelName,
    }));
}
