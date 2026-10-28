import type { OrgNode } from '@meal/shared';

/**
 * Deterministic fake master data for development, tests and the DMP mock.
 *
 * The org tree has 6 levels (BR-61) and only PARTIAL floor / canteen / cost-center mapping, so
 * inheritance (BR-62) is exercised, and it deliberately contains the awkward cases:
 *   - XSC (Xưởng Sửa chữa): meal unit with no floor -> invalid meal unit (BR-63).
 *   - KHO (Kho vận): no meal-unit flag anywhere above -> "unassigned" (BR-60), and no cost center.
 */

export const DMP_LEVEL_NAMES = ['Công ty', 'Khối', 'Phòng/Xưởng', 'Bộ phận', 'Tổ', 'Nhóm'] as const;

export const SEED_FACTORY = {
  id: 'fac_gv',
  code: 'PNJP-GV',
  name: 'PNJP Gò Vấp',
  // TODO(open-questions Q1): real DMP company code is not known yet.
  dmpCompanyCode: 'PNJP',
  timezone: 'Asia/Ho_Chi_Minh',
} as const;

export const SEED_SUPPLIER = {
  id: 'sup_gv',
  name: 'Nhà cung cấp suất ăn Gò Vấp',
  // Placeholder: the real caterer unit price is an external dependency (plan section 8, before P6).
  unitPrice: '35000.00',
} as const;

export const SEED_CANTEEN = { id: 'ct_gv', code: 'CT-GV', name: 'Nhà ăn Gò Vấp' } as const;

/**
 * BR-10 / BR-13: dish types (master data since 2026-10-23). Ids follow the migration that turned
 * the old enum into rows (`dt_<factoryId>_<code>`). The first three keep the plan's usual mix.
 */
export const SEED_DISH_TYPES = [
  { code: 'MAN', name: 'Mặn', color: '#b91c1c', sortOrder: 1, minPerDay: 2, maxPerDay: 3 },
  { code: 'CHAY', name: 'Chay', color: '#15803d', sortOrder: 2, minPerDay: 1, maxPerDay: 1 },
  { code: 'CHAO', name: 'Cháo', color: '#b45309', sortOrder: 3, minPerDay: 1, maxPerDay: 1 },
  {
    code: 'EATCLEAN',
    name: 'Eat clean',
    color: '#0369a1',
    sortOrder: 4,
    minPerDay: null,
    maxPerDay: null,
  },
] as const;
export const dishTypeId = (code: string, factoryId: string = SEED_FACTORY.id) =>
  `dt_${factoryId}_${code}`;

export const SEED_FLOORS = [
  { id: 'fl_T1', code: 'T1', name: 'Tầng 1', sortOrder: 1 },
  { id: 'fl_T2', code: 'T2', name: 'Tầng 2', sortOrder: 2 },
  { id: 'fl_T3', code: 'T3', name: 'Tầng 3', sortOrder: 3 },
  { id: 'fl_T4', code: 'T4', name: 'Tầng 4', sortOrder: 4 },
] as const;

export const SEED_COST_CENTERS = [
  { id: 'cc_CC01', code: 'CC01', name: 'Văn phòng điều hành', source: 'DMP' },
  { id: 'cc_CC02', code: 'CC02', name: 'Hành chánh', source: 'DMP' },
  { id: 'cc_CC03', code: 'CC03', name: 'Sản xuất chế tác', source: 'DMP' },
  { id: 'cc_CC04', code: 'CC04', name: 'Sản xuất kim hoàn', source: 'DMP' },
  { id: 'cc_CC05', code: 'CC05', name: 'Sửa chữa', source: 'DMP' },
  { id: 'cc_CC06', code: 'CC06', name: 'Công nghệ thông tin', source: 'DMP' },
  { id: 'cc_CC07', code: 'CC07', name: 'Dịch vụ và bảo vệ', source: 'DMP' },
  { id: 'cc_CC08', code: 'CC08', name: 'Kinh doanh', source: 'MANUAL' },
  { id: 'cc_CC09', code: 'CC09', name: 'Kho vận', source: 'MANUAL' },
  { id: 'cc_CC10', code: 'CC10', name: 'Chi phí chung', source: 'MANUAL' },
] as const;

export const orgUnitId = (code: string) => `ou_${code}`;
export const floorId = (code: string) => `fl_${code}`;
export const costCenterId = (code: string) => `cc_${code}`;

interface Def {
  code: string;
  name: string;
  /** Meal-unit flag set by the HC admin (BR-60). */
  meal?: true;
  floor?: string;
  cc?: string;
  /** Relative headcount attached directly to this node. */
  hc?: number;
  children?: Def[];
}

const TREE: Def = {
  code: 'PNJP-GV',
  name: 'PNJP Gò Vấp',
  children: [
    {
      code: 'KSX',
      name: 'Khối Sản xuất',
      children: [
        {
          code: 'XCT',
          name: 'Xưởng Chế tác',
          floor: 'T2',
          cc: 'CC03',
          children: [
            {
              code: 'XCT-A',
              name: 'Chuyền A',
              hc: 6,
              children: [
                {
                  code: 'XCT-A1',
                  name: 'Tổ Nhẫn',
                  meal: true,
                  children: [
                    { code: 'XCT-A1-1', name: 'Nhóm Nhẫn 1', hc: 150 },
                    { code: 'XCT-A1-2', name: 'Nhóm Nhẫn 2', hc: 140 },
                  ],
                },
                {
                  code: 'XCT-A2',
                  name: 'Tổ Dây chuyền',
                  meal: true,
                  floor: 'T3',
                  children: [
                    { code: 'XCT-A2-1', name: 'Nhóm Dây 1', hc: 130 },
                    { code: 'XCT-A2-2', name: 'Nhóm Dây 2', hc: 120 },
                  ],
                },
              ],
            },
            {
              code: 'XCT-B',
              name: 'Chuyền B',
              meal: true,
              hc: 5,
              children: [
                {
                  code: 'XCT-B1',
                  name: 'Tổ Lắp ráp',
                  hc: 100,
                  children: [
                    { code: 'XCT-B1-1', name: 'Nhóm Lắp ráp 1', hc: 90 },
                    { code: 'XCT-B1-2', name: 'Nhóm Lắp ráp 2', hc: 80 },
                  ],
                },
              ],
            },
          ],
        },
        {
          code: 'XKH',
          name: 'Xưởng Kim hoàn',
          floor: 'T3',
          cc: 'CC04',
          children: [
            {
              code: 'XKH-DUC',
              name: 'Bộ phận Đúc',
              meal: true,
              hc: 5,
              children: [
                {
                  code: 'XKH-DUC-1',
                  name: 'Tổ Đúc sáp',
                  hc: 20,
                  children: [
                    { code: 'XKH-DUC-1-A', name: 'Nhóm Đúc A', hc: 60 },
                    { code: 'XKH-DUC-1-B', name: 'Nhóm Đúc B', hc: 50 },
                  ],
                },
                { code: 'XKH-DUC-2', name: 'Tổ Hoàn thiện', hc: 70 },
              ],
            },
            {
              code: 'XKH-DA',
              name: 'Bộ phận Đính đá',
              hc: 4,
              children: [
                {
                  code: 'XKH-DA-1',
                  name: 'Tổ Đính đá 1',
                  meal: true,
                  hc: 10,
                  children: [
                    { code: 'XKH-DA-1-A', name: 'Nhóm Đính A', hc: 90 },
                    { code: 'XKH-DA-1-B', name: 'Nhóm Đính B', hc: 80 },
                  ],
                },
                { code: 'XKH-DA-2', name: 'Tổ Đính đá 2', meal: true, hc: 70 },
              ],
            },
          ],
        },
        {
          // No floor anywhere above: its meal unit is invalid (BR-63).
          code: 'XSC',
          name: 'Xưởng Sửa chữa',
          cc: 'CC05',
          children: [{ code: 'XSC-1', name: 'Tổ Sửa chữa', meal: true, hc: 60 }],
        },
      ],
    },
    {
      code: 'KVP',
      name: 'Khối Văn phòng',
      floor: 'T1',
      cc: 'CC01',
      children: [
        {
          code: 'NS',
          name: 'Phòng Nhân sự',
          meal: true,
          hc: 8,
          children: [
            { code: 'NS-HC', name: 'Hành chánh', cc: 'CC02', hc: 25 },
            { code: 'NS-TD', name: 'Tuyển dụng', hc: 15 },
          ],
        },
        {
          code: 'KT',
          name: 'Phòng Kế toán',
          meal: true,
          children: [
            { code: 'KT-TH', name: 'Kế toán tổng hợp', hc: 20 },
            { code: 'KT-CN', name: 'Kế toán công nợ', hc: 18 },
          ],
        },
        { code: 'IT', name: 'Phòng IT', meal: true, floor: 'T4', cc: 'CC06', hc: 30 },
        {
          code: 'KD',
          name: 'Phòng Kinh doanh',
          meal: true,
          floor: 'T4',
          cc: 'CC08',
          children: [
            { code: 'KD-MB', name: 'Kinh doanh miền Bắc', hc: 40 },
            { code: 'KD-OL', name: 'Kinh doanh online', hc: 35 },
          ],
        },
      ],
    },
    {
      code: 'KHT',
      name: 'Khối Hỗ trợ',
      children: [
        { code: 'BV', name: 'Tổ Bảo vệ', meal: true, floor: 'T1', cc: 'CC07', hc: 45 },
        { code: 'VS', name: 'Tổ Vệ sinh', meal: true, floor: 'T1', cc: 'CC07', hc: 40 },
        {
          // No meal-unit flag above or at this branch -> employees are unassigned (BR-60), and
          // no cost center is mapped for XH (BR-63 "missing cost center").
          code: 'KHO',
          name: 'Kho vận',
          floor: 'T1',
          children: [
            { code: 'KHO-NH', name: 'Nhập hàng', hc: 25 },
            { code: 'KHO-XH', name: 'Xuất hàng', hc: 25 },
          ],
        },
      ],
    },
  ],
};

export interface SeedOrgNode {
  id: string;
  factoryId: string;
  dmpCode: string;
  name: string;
  level: number;
  dmpLevelName: string;
  parentId: string | null;
  /** "/ou_A/ou_B/" with trailing slash. */
  path: string;
  isMealUnit: boolean;
  floorId: string | null;
  canteenId: string | null;
  costCenterId: string | null;
  /** Relative headcount used by the employee generator. */
  headcountWeight: number;
}

/** The whole canteen mapping is set once, at the root; everything below inherits it (BR-62). */
export function buildOrgTree(): SeedOrgNode[] {
  const out: SeedOrgNode[] = [];
  const walk = (def: Def, parent: SeedOrgNode | null, level: number) => {
    const id = orgUnitId(def.code);
    const node: SeedOrgNode = {
      id,
      factoryId: SEED_FACTORY.id,
      dmpCode: def.code,
      name: def.name,
      level,
      dmpLevelName: DMP_LEVEL_NAMES[level - 1] ?? `Cấp ${level}`,
      parentId: parent?.id ?? null,
      path: `${parent?.path ?? '/'}${id}/`,
      isMealUnit: def.meal === true,
      floorId: def.floor ? floorId(def.floor) : null,
      canteenId: parent === null ? SEED_CANTEEN.id : null,
      costCenterId: def.cc ? costCenterId(def.cc) : null,
      headcountWeight: def.hc ?? 0,
    };
    out.push(node);
    for (const child of def.children ?? []) walk(child, node, level + 1);
  };
  walk(TREE, null, 1);
  return out;
}

/** Org nodes in the shape the shared mapping rules consume. */
export function toRuleNodes(nodes: SeedOrgNode[]): OrgNode[] {
  return nodes.map((n) => ({
    id: n.id,
    parentId: n.parentId,
    isMealUnit: n.isMealUnit,
    floorId: n.floorId,
    canteenId: n.canteenId,
    costCenterId: n.costCenterId,
  }));
}
