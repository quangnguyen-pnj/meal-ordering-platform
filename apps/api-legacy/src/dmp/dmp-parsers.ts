import { DmpError, type DmpEmployee, type DmpOrgNode } from './dmp.client';

/**
 * Response parsing for the DMP endpoints used from P1 (plan section 5). Swagger has no field
 * descriptions, so every field name below is a best reading of the dmp-backend survey.
 * TODO(BR-61): confirm the field names against DMP /v3/api-docs before syncing real data
 * (plan section 5 and section 8 dependencies).
 */

type Json = Record<string, unknown>;

const str = (v: unknown): string | null =>
  typeof v === 'string' && v.trim() !== '' ? v.trim() : typeof v === 'number' ? String(v) : null;
const pick = (o: Json, keys: string[]): string | null => {
  for (const k of keys) {
    const v = str(o[k]);
    if (v !== null) return v;
  }
  return null;
};

/** GET /api/hierarchy returns the full tree, unpaged: a root node or a list of roots. */
export function parseHierarchy(body: unknown): DmpOrgNode[] {
  const roots = Array.isArray(body) ? body : body && typeof body === 'object' ? [body] : null;
  if (!roots) throw new DmpError('API_ERROR', 'DMP /api/hierarchy returned no tree');
  const out: DmpOrgNode[] = [];
  const walk = (raw: unknown, parentCode: string | null, depth: number) => {
    if (!raw || typeof raw !== 'object') return;
    const node = raw as Json;
    const code = pick(node, ['code', 'unitCode', 'id']);
    if (!code) throw new DmpError('API_ERROR', 'DMP hierarchy node without a code');
    out.push({
      code,
      name: pick(node, ['name', 'unitName']) ?? code,
      parentCode,
      levelName: pick(node, ['levelName', 'level', 'type']) ?? `Cấp ${depth}`,
      costCenterCode: pick(node, ['costCenterCode', 'costCenter']),
    });
    const children = node.children ?? node.childs ?? node.subUnits;
    if (Array.isArray(children)) for (const c of children) walk(c, code, depth + 1);
  };
  for (const r of roots) walk(r, null, 1);
  return out;
}

const toIsoDate = (v: string | null): string | null => {
  if (!v) return null;
  const iso = /^(\d{4}-\d{2}-\d{2})/.exec(v);
  if (iso) return iso[1]!;
  const dmy = /^(\d{2})-(\d{2})-(\d{4})$/.exec(v);
  return dmy ? `${dmy[3]}-${dmy[2]}-${dmy[1]}` : null;
};

/**
 * One page of GET /api/hr-employees (a Spring Page: content, totalElements, last). Only the
 * fields the plan allows are kept; birth date, phone number and gender are dropped.
 */
export function parseEmployeePage(body: unknown): { items: DmpEmployee[]; last: boolean } {
  if (!body || typeof body !== 'object' || !Array.isArray((body as Json).content)) {
    throw new DmpError('API_ERROR', 'DMP /api/hr-employees did not return a page');
  }
  const page = body as Json & { content: unknown[] };
  const items: DmpEmployee[] = [];
  for (const raw of page.content) {
    if (!raw || typeof raw !== 'object') continue;
    const e = raw as Json;
    const employeeCode = pick(e, ['employeeId', 'employeeCode']);
    const orgUnitCode = pick(e, ['unitCode', 'orgUnitCode', 'departmentCode']);
    if (!employeeCode || !orgUnitCode) continue;
    items.push({
      employeeCode,
      name: pick(e, ['fullName', 'name', 'employeeName']) ?? employeeCode,
      email: pick(e, ['email']),
      orgUnitCode,
      costCenterCode: pick(e, ['costCenterCode', 'costCenter']),
      startDate: toIsoDate(pick(e, ['startWDate', 'startDate'])),
      endDate: toIsoDate(pick(e, ['endWDate', 'endDate'])),
    });
  }
  const last =
    page.last === true ||
    page.content.length === 0 ||
    (typeof page.totalPages === 'number' && typeof page.number === 'number'
      ? page.number + 1 >= page.totalPages
      : false);
  return { items, last };
}
