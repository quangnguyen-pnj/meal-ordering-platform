/**
 * Development seed (plan P0): 1 factory, 1 canteen, 4 floors, 10 cost centers, a 6-level org tree
 * with partial mapping, 1,700 fake employees and all 4 roles.
 *
 * Idempotent: re-running adds only what is missing. Refuses to run in production.
 *   pnpm db:seed          (DATABASE_URL must point at a migrated database)
 */
import { PrismaClient } from '@prisma/client';
import * as argon2 from 'argon2';
import { SETTING_KEYS, defaultSettingValue, indexOrgNodes, resolveMealUnitId } from '@meal/shared';
import {
  DEMO,
  SEED_CANTEEN,
  SEED_COST_CENTERS,
  SEED_DISH_TYPES,
  SEED_FACTORY,
  SEED_FLOORS,
  SEED_ROLE_ASSIGNMENTS,
  SEED_SUPPLIER,
  buildEmployees,
  buildOrgTree,
  dishTypeId,
  orgUnitId,
  toRuleNodes,
} from '../../src/seed-data';

const prisma = new PrismaClient();

async function main(): Promise<void> {
  if (process.env.NODE_ENV === 'production') {
    throw new Error('Refusing to seed fake data when NODE_ENV=production');
  }
  const initialPin = process.env.SEED_INITIAL_PIN ?? '123456';
  if (!/^\d{6}$/.test(initialPin)) throw new Error('SEED_INITIAL_PIN must be exactly 6 digits');
  const firstSysAdmin = process.env.FIRST_SYSTEM_ADMIN_CODE?.trim() || DEMO.sysAdmin;

  const factoryId = SEED_FACTORY.id;

  await prisma.factory.upsert({
    where: { id: factoryId },
    update: {},
    create: {
      id: factoryId,
      code: SEED_FACTORY.code,
      name: SEED_FACTORY.name,
      dmpCompanyCode: SEED_FACTORY.dmpCompanyCode,
      timezone: SEED_FACTORY.timezone,
    },
  });

  for (const key of SETTING_KEYS) {
    await prisma.setting.upsert({
      where: { factoryId_key: { factoryId, key } },
      update: {},
      create: { factoryId, key, value: defaultSettingValue(key) as never },
    });
  }

  await prisma.supplier.upsert({
    where: { id: SEED_SUPPLIER.id },
    update: {},
    create: {
      id: SEED_SUPPLIER.id,
      factoryId,
      name: SEED_SUPPLIER.name,
      unitPrice: SEED_SUPPLIER.unitPrice,
    },
  });
  await prisma.canteen.upsert({
    where: { id: SEED_CANTEEN.id },
    update: {},
    create: { ...SEED_CANTEEN, factoryId, supplierId: SEED_SUPPLIER.id },
  });
  await prisma.dishType.createMany({
    data: SEED_DISH_TYPES.map((d) => ({ ...d, id: dishTypeId(d.code, factoryId), factoryId })),
    skipDuplicates: true,
  });
  await prisma.floor.createMany({
    data: SEED_FLOORS.map((f) => ({ ...f, factoryId })),
    skipDuplicates: true,
  });
  await prisma.costCenter.createMany({
    data: SEED_COST_CENTERS.map((c) => ({ ...c, factoryId })),
    skipDuplicates: true,
  });

  const nodes = buildOrgTree();
  await prisma.orgUnit.createMany({
    data: nodes.map(({ headcountWeight: _w, ...n }) => n),
    skipDuplicates: true,
  });

  // BR-60: the meal unit is the nearest flagged node at or above the employee's own node.
  const index = indexOrgNodes(toRuleNodes(nodes));
  // One hash for everyone: every seeded employee starts with the same temporary PIN and must
  // change it at first sign-in (pinMustChange).
  const pinHash = await argon2.hash(initialPin, { type: argon2.argon2id });

  const employees = buildEmployees(nodes);
  await prisma.employee.createMany({
    data: employees.map((e) => ({
      employeeCode: e.employeeCode,
      name: e.name,
      email: e.email,
      orgUnitId: e.orgUnitId,
      mealUnitId: resolveMealUnitId(e.orgUnitId, index),
      dmpCostCenterCode: e.dmpCostCenterCode,
      startDate: new Date(`${e.startDate}T00:00:00.000Z`),
      pinHash,
      pinMustChange: true,
    })),
    skipDuplicates: true,
  });

  const byCode = new Map(
    (await prisma.employee.findMany({ select: { id: true, employeeCode: true } })).map((e) => [
      e.employeeCode,
      e.id,
    ]),
  );
  const assignments = [
    ...SEED_ROLE_ASSIGNMENTS.filter((a) => a.role !== 'SYS_ADMIN'),
    { employeeCode: firstSysAdmin, role: 'SYS_ADMIN' as const, orgUnitCode: null },
  ];
  for (const a of assignments) {
    const employeeId = byCode.get(a.employeeCode);
    if (!employeeId)
      throw new Error(`Role assignment refers to unknown employee ${a.employeeCode}`);
    const orgId = a.orgUnitCode ? orgUnitId(a.orgUnitCode) : null;
    const existing = await prisma.roleAssignment.findFirst({
      where: { employeeId, role: a.role, orgUnitId: orgId },
    });
    if (!existing) {
      await prisma.roleAssignment.create({
        data: { employeeId, role: a.role, factoryId, orgUnitId: orgId },
      });
    }
  }

  const counts = {
    factories: await prisma.factory.count(),
    canteens: await prisma.canteen.count(),
    floors: await prisma.floor.count(),
    costCenters: await prisma.costCenter.count(),
    orgUnits: await prisma.orgUnit.count(),
    employees: await prisma.employee.count(),
    roleAssignments: await prisma.roleAssignment.count(),
  };
  console.log('Seed complete:', counts);
  console.log(
    `Demo sign-ins (initial PIN from SEED_INITIAL_PIN, change required at first sign-in): ` +
      `employee ${DEMO.employee}, dept admin ${DEMO.deptAdminProduction}, HC admin ${DEMO.hcAdmin}, system admin ${firstSysAdmin}`,
  );
}

main()
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  })
  .finally(() => prisma.$disconnect());
