import type { SeedOrgNode } from './org-tree';

export const SEED_EMPLOYEE_COUNT = 1700;

export interface SeedEmployee {
  employeeCode: string;
  name: string;
  email: string;
  orgUnitId: string;
  dmpCostCenterCode: string | null;
  /** YYYY-MM-DD */
  startDate: string;
}

export interface SeedRoleAssignment {
  employeeCode: string;
  role: 'DEPT_ADMIN' | 'HC_ADMIN' | 'SYS_ADMIN';
  /** Org node code, only for DEPT_ADMIN. */
  orgUnitCode: string | null;
}

/** Fixed accounts used by docs, demos and tests (all four roles, plus awkward cases). */
export const DEMO = {
  employee: 'PNJP000001',
  deptAdminProduction: 'PNJP000002',
  hcAdmin: 'PNJP000003',
  sysAdmin: 'PNJP000004',
  deptAdminJewelry: 'PNJP000005',
  /** Under KHO: no meal-unit flag above -> unassigned (BR-60). */
  unassigned: 'PNJP000006',
  /** Under XSC: meal unit has no floor -> invalid (BR-63). */
  invalidMealUnit: 'PNJP000007',
} as const;

const DEMO_ACCOUNTS: Array<{ code: string; name: string; email: string; node: string }> = [
  { code: DEMO.employee, name: 'Nguyễn Văn An', email: 'an.nguyen@pnjp.test', node: 'XCT-A1-1' },
  {
    code: DEMO.deptAdminProduction,
    name: 'Trần Thị Bình',
    email: 'binh.tran@pnjp.test',
    node: 'XCT-A',
  },
  { code: DEMO.hcAdmin, name: 'Lê Hoàng Cường', email: 'cuong.le@pnjp.test', node: 'NS-HC' },
  { code: DEMO.sysAdmin, name: 'Phạm Minh Dũng', email: 'dung.pham@pnjp.test', node: 'IT' },
  { code: DEMO.deptAdminJewelry, name: 'Võ Thị Em', email: 'em.vo@pnjp.test', node: 'XKH-DA-1' },
  { code: DEMO.unassigned, name: 'Đặng Quốc Phong', email: 'phong.dang@pnjp.test', node: 'KHO-XH' },
  {
    code: DEMO.invalidMealUnit,
    name: 'Bùi Thị Giang',
    email: 'giang.bui@pnjp.test',
    node: 'XSC-1',
  },
];

export const SEED_ROLE_ASSIGNMENTS: SeedRoleAssignment[] = [
  { employeeCode: DEMO.deptAdminProduction, role: 'DEPT_ADMIN', orgUnitCode: 'XCT' },
  { employeeCode: DEMO.deptAdminJewelry, role: 'DEPT_ADMIN', orgUnitCode: 'XKH' },
  { employeeCode: DEMO.hcAdmin, role: 'HC_ADMIN', orgUnitCode: null },
  { employeeCode: DEMO.sysAdmin, role: 'SYS_ADMIN', orgUnitCode: null },
];

const FAMILY = [
  'Nguyễn',
  'Trần',
  'Lê',
  'Phạm',
  'Hoàng',
  'Huỳnh',
  'Phan',
  'Vũ',
  'Võ',
  'Đặng',
  'Bùi',
  'Đỗ',
  'Hồ',
  'Ngô',
  'Dương',
  'Lý',
];
const MIDDLE_F = ['Thị', 'Ngọc', 'Thanh', 'Kim', 'Hồng', 'Thu'];
const MIDDLE_M = ['Văn', 'Minh', 'Quốc', 'Hữu', 'Đức', 'Anh'];
const GIVEN_F = [
  'Lan',
  'Hoa',
  'Mai',
  'Linh',
  'Trang',
  'Hương',
  'Thảo',
  'Yến',
  'Nhung',
  'Ngân',
  'Phương',
  'Oanh',
];
const GIVEN_M = [
  'Hùng',
  'Nam',
  'Long',
  'Khánh',
  'Tuấn',
  'Hải',
  'Phúc',
  'Thịnh',
  'Sơn',
  'Quân',
  'Bảo',
  'Khoa',
];

/** Deterministic PRNG so every run (and every test) sees the same people. */
function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function toAscii(value: string): string {
  return value
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/đ/g, 'd')
    .replace(/Đ/g, 'D')
    .toLowerCase();
}

/** Largest-remainder allocation of `total` seats over positive weights. */
function allocate(weights: number[], total: number): number[] {
  const sum = weights.reduce((a, b) => a + b, 0);
  const exact = weights.map((w) => (w / sum) * total);
  const counts = exact.map(Math.floor);
  let left = total - counts.reduce((a, b) => a + b, 0);
  const order = exact
    .map((v, i) => ({ i, frac: v - Math.floor(v) }))
    .sort((x, y) => y.frac - x.frac || x.i - y.i);
  for (const { i } of order) {
    if (left <= 0) break;
    counts[i] = (counts[i] ?? 0) + 1;
    left -= 1;
  }
  return counts;
}

const pick = <T>(rand: () => number, items: readonly T[]): T =>
  items[Math.floor(rand() * items.length)] as T;

/**
 * 1,700 fake employees: the 7 demo accounts first, then the rest spread over the org tree by
 * headcount weight. About 1 in 97 has an employee-level cost-center override (BR-63), and the
 * Nhập hàng team carries a DMP-supplied cost center to exercise the DMP default (BR-62).
 */
export function buildEmployees(nodes: SeedOrgNode[]): SeedEmployee[] {
  const rand = mulberry32(20261019);
  const byCode = new Map(nodes.map((n) => [n.dmpCode, n]));
  const employees: SeedEmployee[] = [];
  let seq = 0;

  const startDate = () => {
    const year = 2012 + Math.floor(rand() * 15);
    const month = 1 + Math.floor(rand() * 12);
    const day = 1 + Math.floor(rand() * 28);
    return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
  };
  const nextCode = () => `PNJP${String(++seq).padStart(6, '0')}`;
  const dmpCcFor = (nodeCode: string) => (nodeCode === 'KHO-NH' ? 'CC09' : null);

  for (const demo of DEMO_ACCOUNTS) {
    const code = nextCode();
    if (code !== demo.code) throw new Error(`Demo account order broken: ${code} != ${demo.code}`);
    const node = byCode.get(demo.node);
    if (!node) throw new Error(`Unknown demo node ${demo.node}`);
    employees.push({
      employeeCode: code,
      name: demo.name,
      email: demo.email,
      orgUnitId: node.id,
      dmpCostCenterCode: dmpCcFor(demo.node),
      startDate: startDate(),
    });
  }

  const populated = nodes.filter((n) => n.headcountWeight > 0);
  const counts = allocate(
    populated.map((n) => n.headcountWeight),
    SEED_EMPLOYEE_COUNT - DEMO_ACCOUNTS.length,
  );
  populated.forEach((node, i) => {
    for (let k = 0; k < (counts[i] ?? 0); k++) {
      const code = nextCode();
      const female = rand() < 0.55;
      const family = pick(rand, FAMILY);
      const middle = pick(rand, female ? MIDDLE_F : MIDDLE_M);
      const given = pick(rand, female ? GIVEN_F : GIVEN_M);
      employees.push({
        employeeCode: code,
        name: `${family} ${middle} ${given}`,
        email: `${toAscii(given)}.${toAscii(family)}${seq}@pnjp.test`,
        orgUnitId: node.id,
        dmpCostCenterCode: dmpCcFor(node.dmpCode),
        startDate: startDate(),
      });
    }
  });

  if (employees.length !== SEED_EMPLOYEE_COUNT) {
    throw new Error(`Expected ${SEED_EMPLOYEE_COUNT} employees, generated ${employees.length}`);
  }
  return employees;
}
