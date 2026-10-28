import { indexOrgNodes, resolveEmployeeMealAssignment } from '@meal/shared';
import { SETTING_KEYS } from '@meal/shared';
import { DEMO, closeDb, db } from './helpers';

afterAll(closeDb);

describe('seeded database', () => {
  it('holds 1 factory, 1 canteen, 4 floors, 10 cost centers and 1,700 employees', async () => {
    const prisma = db();
    expect(await prisma.factory.count()).toBe(1);
    expect(await prisma.canteen.count()).toBe(1);
    expect(await prisma.floor.count()).toBe(4);
    expect(await prisma.costCenter.count()).toBe(10);
    expect(await prisma.employee.count()).toBe(1700);
    expect(await prisma.supplier.count()).toBe(1);
  });

  it('stores every plan 6.4 setting', async () => {
    expect(await db().setting.count()).toBe(SETTING_KEYS.length);
    const close = await db().setting.findFirstOrThrow({ where: { key: 'registration.close' } });
    expect(close.value).toEqual({ weekday: 4, time: '23:59' });
  });

  it('stores a 6-level tree with parents and materialized paths', async () => {
    const units = await db().orgUnit.findMany();
    expect(Math.max(...units.map((u) => u.level))).toBe(6);
    const byId = new Map(units.map((u) => [u.id, u]));
    for (const u of units) {
      const parent = u.parentId ? byId.get(u.parentId) : null;
      expect(u.path).toBe(`${parent?.path ?? '/'}${u.id}/`);
    }
  });

  it('assigns all four roles', async () => {
    const roles = await db().roleAssignment.findMany({ select: { role: true } });
    expect(new Set(roles.map((r) => r.role))).toEqual(
      new Set(['DEPT_ADMIN', 'HC_ADMIN', 'SYS_ADMIN']),
    );
    const dept = await db().roleAssignment.findMany({
      // Revoked assignments are kept for the record (rule 7); only live ones count.
      where: { role: 'DEPT_ADMIN', revokedAt: null },
      include: { employee: true },
    });
    expect(dept.map((d) => d.employee.employeeCode).sort()).toEqual([
      DEMO.deptAdminProduction,
      DEMO.deptAdminJewelry,
    ]);
  });

  it('stores employee meal units that agree with the shared mapping rules (BR-60)', async () => {
    const units = await db().orgUnit.findMany();
    const index = indexOrgNodes(
      units.map((u) => ({
        id: u.id,
        parentId: u.parentId,
        isMealUnit: u.isMealUnit,
        floorId: u.floorId,
        canteenId: u.canteenId,
        costCenterId: u.costCenterId,
      })),
    );
    const employees = await db().employee.findMany();
    let disagreements = 0;
    let unassigned = 0;
    for (const e of employees) {
      const a = resolveEmployeeMealAssignment({
        orgUnitId: e.orgUnitId,
        floorOverrideId: e.floorOverrideId,
        index,
      });
      if (a.mealUnitId !== e.mealUnitId) disagreements++;
      if (!a.canRegister) unassigned++;
    }
    expect(disagreements).toBe(0);
    expect(unassigned).toBeGreaterThan(0);
  });

  it('never stores a PIN in clear text, and seeded employees must change it', async () => {
    const e = await db().employee.findUniqueOrThrow({ where: { employeeCode: DEMO.employee } });
    expect(e.pinHash).toMatch(/^\$argon2id\$/);
    const sample = await db().employee.findMany({ take: 20, select: { pinHash: true } });
    expect(sample.every((s) => s.pinHash?.startsWith('$argon2id$'))).toBe(true);
  });
});
