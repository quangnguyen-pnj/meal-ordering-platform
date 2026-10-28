import {
  indexOrgNodes,
  resolveEmployeeMealAssignment,
  resolveMapping,
  resolveMealUnitId,
  resolveMealUnitMapping,
} from '@meal/shared';
import {
  DEMO,
  SEED_COST_CENTERS,
  SEED_EMPLOYEE_COUNT,
  SEED_FLOORS,
  buildEmployees,
  buildOrgTree,
  costCenterId,
  floorId,
  orgUnitId,
  toRuleNodes,
} from './index';

const nodes = buildOrgTree();
const index = indexOrgNodes(toRuleNodes(nodes));
const employees = buildEmployees(nodes);
const assignmentOf = (orgUnit: string, override: string | null = null) =>
  resolveEmployeeMealAssignment({
    orgUnitId: orgUnitId(orgUnit),
    floorOverrideId: override,
    index,
  });

describe('seed data shape', () => {
  it('has a 6-level org tree (BR-61) with every level used', () => {
    const levels = new Set(nodes.map((n) => n.level));
    expect([...levels].sort()).toEqual([1, 2, 3, 4, 5, 6]);
    expect(nodes.every((n) => n.level >= 1 && n.level <= 6)).toBe(true);
  });

  it('has 4 floors, 10 cost centers and exactly 1,700 uniquely coded employees', () => {
    expect(SEED_FLOORS).toHaveLength(4);
    expect(SEED_COST_CENTERS).toHaveLength(10);
    expect(employees).toHaveLength(SEED_EMPLOYEE_COUNT);
    expect(new Set(employees.map((e) => e.employeeCode)).size).toBe(SEED_EMPLOYEE_COUNT);
    expect(new Set(employees.map((e) => e.email)).size).toBe(SEED_EMPLOYEE_COUNT);
  });

  it('is deterministic across runs', () => {
    expect(buildEmployees(buildOrgTree())).toEqual(employees);
  });

  it('stores a materialized path ending in the node id for every node', () => {
    for (const n of nodes) {
      expect(n.path.endsWith(`/${n.id}/`)).toBe(true);
      expect(n.path.startsWith('/')).toBe(true);
    }
  });

  it('maps only part of the tree directly, so inheritance is exercised (BR-62)', () => {
    const withFloor = nodes.filter((n) => n.floorId !== null).length;
    const withCostCenter = nodes.filter((n) => n.costCenterId !== null).length;
    expect(withFloor).toBeGreaterThan(0);
    expect(withFloor).toBeLessThan(nodes.length / 2);
    expect(withCostCenter).toBeLessThan(nodes.length / 2);
    expect(nodes.filter((n) => n.canteenId !== null)).toHaveLength(1); // only the root
  });
});

describe('BR-62 mapping resolution on the 6-level seed tree', () => {
  it('BR-62: a level-6 node inherits the canteen from the root', () => {
    expect(resolveMapping(orgUnitId('XCT-A1-1'), 'canteenId', index)).toMatchObject({
      value: 'ct_gv',
      sourceNodeId: orgUnitId('PNJP-GV'),
      inherited: true,
    });
  });

  it('BR-62: the nearest ancestor wins (Tổ Dây chuyền floor T3 beats Xưởng Chế tác floor T2)', () => {
    expect(resolveMapping(orgUnitId('XCT-A2-1'), 'floorId', index)).toMatchObject({
      value: floorId('T3'),
      sourceNodeId: orgUnitId('XCT-A2'),
    });
    expect(resolveMapping(orgUnitId('XCT-A1-1'), 'floorId', index).value).toBe(floorId('T2'));
  });

  it('BR-62: Hành chánh overrides the cost center its parent block inherits', () => {
    expect(resolveMapping(orgUnitId('NS-HC'), 'costCenterId', index).value).toBe(
      costCenterId('CC02'),
    );
    expect(resolveMapping(orgUnitId('NS-TD'), 'costCenterId', index).value).toBe(
      costCenterId('CC01'),
    );
  });
});

describe('BR-60 / BR-63 on the seed tree', () => {
  it('BR-60: a level-6 worker belongs to the nearest flagged node above (Tổ Nhẫn)', () => {
    expect(resolveMealUnitId(orgUnitId('XCT-A1-1'), index)).toBe(orgUnitId('XCT-A1'));
  });

  it('BR-63: floor and canteen come from the meal unit, not the employee node', () => {
    // Tổ Dây chuyền is a meal unit on floor T3 even though its workshop is on T2.
    expect(resolveMealUnitMapping(orgUnitId('XCT-A2'), index)).toEqual({
      valid: true,
      floorId: floorId('T3'),
      canteenId: 'ct_gv',
    });
  });

  it('BR-63: the Sửa chữa meal unit has no floor, so it is invalid', () => {
    expect(resolveMealUnitMapping(orgUnitId('XSC-1'), index)).toEqual({
      valid: false,
      missing: ['floor'],
    });
    expect(assignmentOf('XSC-1')).toMatchObject({
      status: 'INVALID_MEAL_UNIT',
      canRegister: false,
    });
  });

  it('BR-60: Kho vận employees have no flagged ancestor, so they are unassigned', () => {
    expect(assignmentOf('KHO-XH')).toMatchObject({ status: 'NO_MEAL_UNIT', canRegister: false });
  });

  it('BR-63: cost center follows the org tree; an employee pick-up floor beats the meal unit floor', () => {
    expect(assignmentOf('XCT-A1-1')).toMatchObject({
      costCenterId: costCenterId('CC03'),
      costCenterSource: 'MAPPING',
      floorId: floorId('T2'),
      floorSource: 'MEAL_UNIT',
    });
    expect(assignmentOf('XCT-A1-1', floorId('T1'))).toMatchObject({
      costCenterId: costCenterId('CC03'),
      floorId: floorId('T1'),
      floorSource: 'OVERRIDE',
    });
  });

  it('BR-63: Kho vận Xuất hàng has no cost center, but registration is not blocked by that', () => {
    const a = resolveEmployeeMealAssignment({
      orgUnitId: orgUnitId('XCT-B1-1'),
      floorOverrideId: null,
      index,
    });
    expect(a.canRegister).toBe(true);
    expect(assignmentOf('KHO-XH')).toMatchObject({ missingCostCenter: true });
  });

  it('every seeded employee resolves without throwing, with a plausible mix of outcomes', () => {
    const counts = { OK: 0, NO_MEAL_UNIT: 0, INVALID_MEAL_UNIT: 0 };
    for (const e of employees) {
      counts[
        resolveEmployeeMealAssignment({
          orgUnitId: e.orgUnitId,
          floorOverrideId: null,
          index,
        }).status
      ] += 1;
    }
    expect(counts.OK).toBeGreaterThan(1400);
    expect(counts.NO_MEAL_UNIT).toBeGreaterThan(0);
    expect(counts.INVALID_MEAL_UNIT).toBeGreaterThan(0);
  });

  it('puts the demo accounts where the docs say', () => {
    const where = (code: string) => employees.find((e) => e.employeeCode === code)?.orgUnitId;
    expect(where(DEMO.employee)).toBe(orgUnitId('XCT-A1-1'));
    expect(where(DEMO.unassigned)).toBe(orgUnitId('KHO-XH'));
    expect(where(DEMO.invalidMealUnit)).toBe(orgUnitId('XSC-1'));
  });
});
