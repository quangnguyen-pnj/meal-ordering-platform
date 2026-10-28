import type { INestApplication } from '@nestjs/common';
import type { MappingImportView, OrgUnitView } from '@meal/shared';
import ExcelJS from 'exceljs';
import request from 'supertest';
import { AuditService } from '../src/audit/audit.service';
import { ClockService } from '../src/clock/clock.service';
import type { DmpClient, DmpEmployee, DmpOrgNode } from '../src/dmp/dmp.client';
import { DmpError } from '../src/dmp/dmp.client';
import { MockDmpClient } from '../src/dmp/mock/mock-dmp.client';
import { JobsService } from '../src/jobs/jobs.service';
import { MAPPING_TEMPLATE_HEADER } from '../src/org/mapping-import.service';
import { OrgSyncService } from '../src/org/org-sync.service';
import { PrismaService } from '../src/prisma/prisma.service';
import { SystemAdminBootstrap } from '../src/rbac/system-admin-bootstrap.service';
import { SettingsService } from '../src/settings/settings.service';
import {
  DEMO,
  closeDb,
  createTestApp,
  db,
  expectForbidden,
  signInReady,
  type Agent,
  type Endpoint,
} from './helpers';

let app: INestApplication;
let hc: Agent;
let sys: Agent;
let employee: Agent;
let deptProd: Agent;

/** The own mapping of every org node and every employee override, to prove "nothing changed". */
async function mappingSnapshot() {
  const units = await db().orgUnit.findMany({
    select: { id: true, isMealUnit: true, floorId: true, canteenId: true, costCenterId: true },
    orderBy: { id: 'asc' },
  });
  const overrides = await db().employee.findMany({
    where: { floorOverrideId: { not: null } },
    select: { employeeCode: true, floorOverrideId: true },
    orderBy: { employeeCode: 'asc' },
  });
  return { units, overrides };
}

type Cell = string | null;
async function xlsx(rows: Cell[][], header: readonly string[] = MAPPING_TEMPLATE_HEADER) {
  const wb = new ExcelJS.Workbook();
  const sheet = wb.addWorksheet('Mapping');
  sheet.addRow([...header]);
  for (const r of rows) sheet.addRow(r.map((c) => c ?? ''));
  return Buffer.from(await wb.xlsx.writeBuffer());
}
// Columns: type, code, name (ignored), floor, canteen, cost center.
const orgRow = (code: string, floor: Cell, canteen: Cell, cc: Cell): Cell[] => [
  'ORG',
  code,
  '',
  floor,
  canteen,
  cc,
];

const upload = (agent: Agent, buffer: Buffer, name = 'mapping.xlsx') =>
  agent.post('/api/admin/org/mapping/imports').attach('file', buffer, name);

const binary = (res: request.Response, cb: (err: Error | null, body: Buffer) => void) => {
  const chunks: Buffer[] = [];
  res.on('data', (c: Buffer) => chunks.push(c));
  res.on('end', () => cb(null, Buffer.concat(chunks)));
};

beforeAll(async () => {
  app = await createTestApp();
  [hc, sys, employee, deptProd] = await Promise.all([
    signInReady(app, DEMO.hcAdmin),
    signInReady(app, DEMO.sysAdmin),
    signInReady(app, 'PNJP000080'),
    signInReady(app, DEMO.deptAdminProduction),
  ]);
});
afterAll(async () => {
  await app.close();
  await closeDb();
});

describe('org tree (BR-61, BR-62)', () => {
  it('BR-61: shows up to 6 levels with the DMP level name', async () => {
    const res = await hc.get('/api/admin/org/tree').expect(200);
    const items: OrgUnitView[] = res.body.items;
    expect(Math.max(...items.map((u) => u.level))).toBe(6);
    const group = items.find((u) => u.dmpCode === 'XCT-A1-1')!;
    expect(group).toMatchObject({ level: 6, dmpLevelName: 'Nhóm' });
  });

  it('BR-62: inherited values name the node they come from', async () => {
    const items: OrgUnitView[] = (await hc.get('/api/admin/org/tree').expect(200)).body.items;
    const team = items.find((u) => u.dmpCode === 'XCT-A1')!; // Tổ Nhẫn
    expect(team.own.floorId).toBeNull();
    expect(team.resolved.floor).toEqual({ id: 'fl_T2', sourceNodeId: 'ou_XCT', inherited: true });
    expect(team.resolved.canteen).toEqual({
      id: 'ct_gv',
      sourceNodeId: 'ou_PNJP-GV',
      inherited: true,
    });
    expect(team.isMealUnit).toBe(true);
    expect(team.mealUnitValid).toBe(true);
    const repair = items.find((u) => u.dmpCode === 'XSC-1')!;
    expect(repair.mealUnitValid).toBe(false); // BR-63: no floor anywhere above
  });

  it('BR-60: every node names the meal unit its employees belong to', async () => {
    const items: OrgUnitView[] = (await hc.get('/api/admin/org/tree').expect(200)).body.items;
    const byCode = new Map(items.map((u) => [u.dmpCode, u]));
    const team = byCode.get('XCT-A1')!; // Tổ Nhẫn, flagged
    expect(team.mealUnitId).toBe(team.id);
    expect(byCode.get('XCT-A1-1')!.mealUnitId).toBe(team.id); // its group follows it
    const warehouse = items.find((u) => u.name === 'Kho vận')!;
    expect(warehouse.mealUnitId).toBeNull();
  });
});

describe('meal-unit flags and lists (BR-60, BR-63)', () => {
  it('BR-60 / BR-63: the unassigned list says why; the missing-cost-center list is separate', async () => {
    const unassigned = (await hc.get('/api/admin/org/unassigned').expect(200)).body.items;
    const byCode = new Map(unassigned.map((e: { employeeCode: string }) => [e.employeeCode, e]));
    expect(byCode.get(DEMO.unassigned)).toMatchObject({ mealStatus: 'NO_MEAL_UNIT' });
    expect(byCode.get(DEMO.invalidMealUnit)).toMatchObject({
      mealStatus: 'INVALID_MEAL_UNIT',
      missing: ['floor'],
    });
    expect(byCode.has(DEMO.employee)).toBe(false);

    const missing = (await hc.get('/api/admin/org/missing-cost-center').expect(200)).body.items;
    expect(missing.length).toBeGreaterThan(0);
    expect(missing.every((e: { costCenterId: null }) => e.costCenterId === null)).toBe(true);
    expect(missing.every((e: { orgPath: string }) => e.orgPath.includes('Kho vận'))).toBe(true);
  });

  it('BR-60: flagging a node recomputes meal units at once, and it is audited', async () => {
    await hc.patch('/api/admin/org/units/ou_KHO').send({ isMealUnit: true }).expect(200);
    const moved = await db().employee.findUniqueOrThrow({
      where: { employeeCode: DEMO.unassigned },
    });
    expect(moved.mealUnitId).toBe('ou_KHO');
    const unassigned = (await hc.get('/api/admin/org/unassigned').expect(200)).body.items;
    expect(
      unassigned.some((e: { employeeCode: string }) => e.employeeCode === DEMO.unassigned),
    ).toBe(false);

    await hc.patch('/api/admin/org/units/ou_KHO').send({ isMealUnit: false }).expect(200);
    const back = await db().employee.findUniqueOrThrow({
      where: { employeeCode: DEMO.unassigned },
    });
    expect(back.mealUnitId).toBeNull();
    const audits = await db().auditLog.findMany({
      where: { entityId: 'ou_KHO', action: 'orgUnit.mapping.updated' },
      orderBy: { at: 'asc' },
    });
    expect(audits.slice(-2).map((a) => a.after)).toEqual([
      { isMealUnit: true },
      { isMealUnit: false },
    ]);
  });

  it('BR-62: a node maps to active master data only; null clears its own value', async () => {
    await hc.patch('/api/admin/org/units/ou_XSC').send({ floorId: 'nope' }).expect(400);
    await hc.patch('/api/admin/org/units/ou_XSC').send({ floorId: 'fl_T4' }).expect(200);
    let items: OrgUnitView[] = (await hc.get('/api/admin/org/tree')).body.items;
    expect(items.find((u) => u.dmpCode === 'XSC-1')!.mealUnitValid).toBe(true);
    await hc.patch('/api/admin/org/units/ou_XSC').send({ floorId: null }).expect(200);
    items = (await hc.get('/api/admin/org/tree')).body.items;
    expect(items.find((u) => u.dmpCode === 'XSC-1')!.mealUnitValid).toBe(false);
    await hc.patch('/api/admin/org/units/ou_XSC').send({}).expect(400);
  });
});

describe('assigning dept admins', () => {
  const NEW_ADMIN = 'PNJP000090';

  it('the HC admin assigns a dept admin to a node, whose scope then covers that branch only', async () => {
    const res = await hc
      .post('/api/admin/org/units/ou_KD/dept-admins')
      .send({ employeeCode: NEW_ADMIN })
      .expect(201);
    const admin = await signInReady(app, NEW_ADMIN);
    const list = await admin.get('/api/employees?limit=100').expect(200);
    expect(list.body.items.length).toBeGreaterThan(0);
    expect(
      list.body.items.every((e: { orgPath: string }) => e.orgPath.includes('Phòng Kinh doanh')),
    ).toBe(true);
    const tree: OrgUnitView[] = (await hc.get('/api/admin/org/tree')).body.items;
    expect(tree.find((u) => u.id === 'ou_KD')!.deptAdmins.map((a) => a.employeeCode)).toEqual([
      NEW_ADMIN,
    ]);

    await hc.post(`/api/admin/org/dept-admins/${res.body.id}/revoke`).expect(200);
    await admin.get('/api/employees').expect(403);
    const row = await db().roleAssignment.findUniqueOrThrow({ where: { id: res.body.id } });
    expect(row.revokedAt).not.toBeNull(); // kept for the record, never deleted
  });

  it('rejects an unknown employee', async () => {
    await hc
      .post('/api/admin/org/units/ou_KD/dept-admins')
      .send({ employeeCode: 'NOPE' })
      .expect(400);
  });
});

describe('BR-64: mapping export and import', () => {
  let exported: Buffer;

  it('BR-64: exports the import template with the current own mapping', async () => {
    const res = await hc
      .get('/api/admin/org/mapping/export')
      .buffer(true)
      .parse(binary)
      .expect(200);
    expect(res.headers['content-type']).toContain('spreadsheetml');
    exported = res.body as Buffer;
    const wb = new ExcelJS.Workbook();
    await wb.xlsx.load(exported as unknown as ArrayBuffer);
    const sheet = wb.worksheets[0]!;
    expect(sheet.getRow(1).getCell(1).text).toBe(MAPPING_TEMPLATE_HEADER[0]);
    const rows: string[][] = [];
    sheet.eachRow((row, n) => {
      if (n > 1) rows.push([1, 2, 4, 5, 6].map((c) => row.getCell(c).text));
    });
    expect(rows).toContainEqual(['ORG', 'XCT', 'T2', '', 'CC03']);
    expect(rows).toContainEqual(['ORG', 'PNJP-GV', '', 'CT-GV', '']);
    expect(rows.filter((r) => r[0] === 'ORG')).toHaveLength(await db().orgUnit.count());
  });

  it('BR-64 exit criterion: one bad row rejects the file with row-level errors and applies nothing', async () => {
    const before = await mappingSnapshot();
    const auditsBefore = await db().auditLog.count();
    const file = await xlsx([
      orgRow('XCT', 'T1', null, 'CC03'), // valid on its own
      orgRow('KVP', 'T9', null, null), // unknown floor
      orgRow('NOPE', null, null, null), // unknown node
      ['EMPLOYEE', DEMO.employee, '', 'T1', null, 'CC01'], // only the pick-up floor (BR-63)
    ]);
    const res = await upload(hc, file).expect(201);
    const view: MappingImportView = res.body;
    expect(view.status).toBe('REJECTED');
    expect(view.rowCount).toBe(4);
    expect(view.errors).toEqual([
      { rowNumber: 3, field: 'floorCode', code: 'FLOOR_UNKNOWN', value: 'T9' },
      { rowNumber: 4, field: 'code', code: 'ORG_UNKNOWN', value: 'NOPE' },
      {
        rowNumber: 5,
        field: 'costCenterCode',
        code: 'EMPLOYEE_CANTEEN_OR_COST_CENTER',
        value: 'CC01',
      },
    ]);
    expect(await mappingSnapshot()).toEqual(before);
    expect(await db().auditLog.count()).toBe(auditsBefore);
    const apply = await hc.post(`/api/admin/org/mapping/imports/${view.id}/apply`).expect(409);
    expect(apply.body.code).toBe('INVALID_STATE');
    expect(await mappingSnapshot()).toEqual(before);
  });

  it('BR-64: a valid file previews its changes and applies them all at once, audited', async () => {
    const before = await mappingSnapshot();
    const file = await xlsx([
      orgRow('XCT', 'T1', null, 'CC03'), // floor T2 -> T1
      orgRow('KHO-XH', null, null, 'CC09'), // cost center for the "missing" list
      orgRow('IT', 'T4', null, 'CC06'), // unchanged: not in the preview
      ['EMPLOYEE', DEMO.employee, '', 'T1', null, null],
    ]);
    const preview: MappingImportView = (await upload(hc, file).expect(201)).body;
    expect(preview.status).toBe('PREVIEW');
    expect(preview.changes.map((c) => [c.type, c.code])).toEqual([
      ['ORG', 'XCT'],
      ['ORG', 'KHO-XH'],
      ['EMPLOYEE', DEMO.employee],
    ]);
    expect(preview.changes[0]).toMatchObject({
      before: { floorCode: 'T2', canteenCode: null, costCenterCode: 'CC03' },
      after: { floorCode: 'T1', canteenCode: null, costCenterCode: 'CC03' },
    });
    expect(await mappingSnapshot()).toEqual(before); // a preview applies nothing

    const applied: MappingImportView = (
      await hc.post(`/api/admin/org/mapping/imports/${preview.id}/apply`).expect(200)
    ).body;
    expect(applied.status).toBe('APPLIED');
    expect((await db().orgUnit.findUniqueOrThrow({ where: { id: 'ou_XCT' } })).floorId).toBe(
      'fl_T1',
    );
    expect(
      (await db().employee.findUniqueOrThrow({ where: { employeeCode: DEMO.employee } }))
        .floorOverrideId,
    ).toBe('fl_T1');
    const audit = await db().auditLog.findFirstOrThrow({
      where: { entityId: 'ou_XCT', action: 'orgUnit.mapping.updated' },
      orderBy: { at: 'desc' },
    });
    expect(audit.reason).toContain(preview.id);
    await hc.post(`/api/admin/org/mapping/imports/${preview.id}/apply`).expect(409);
  });

  it('BR-64: re-importing the exported file restores the mapping (blank = inherit)', async () => {
    const preview: MappingImportView = (await upload(hc, exported, 'export.xlsx').expect(201)).body;
    expect(preview.status).toBe('PREVIEW');
    // The exported file has no row for the employee, so their pick-up floor stays.
    expect(preview.changes.map((c) => c.code).sort()).toEqual(['KHO-XH', 'XCT']);
    await hc.post(`/api/admin/org/mapping/imports/${preview.id}/apply`).expect(200);
    await hc
      .put(`/api/employees/${DEMO.employee}/floor-override`)
      .send({ floorId: null, reason: 'Về lại tầng của đơn vị' })
      .expect(200);
    expect((await db().orgUnit.findUniqueOrThrow({ where: { id: 'ou_XCT' } })).floorId).toBe(
      'fl_T2',
    );
  });

  it('rejects a file that is not the template, and a request without a file', async () => {
    const res = await upload(hc, await xlsx([['x']], ['Something else'])).expect(400);
    expect(res.body.details).toEqual([{ path: 'file', message: 'TEMPLATE_MISMATCH' }]);
    await upload(hc, Buffer.from('not a spreadsheet'), 'x.xlsx').expect(400);
    await hc.post('/api/admin/org/mapping/imports').expect(400);
  });
});

describe('BR-64 exit criterion: changing a mapping leaves existing snapshot rows unchanged', () => {
  it('BR-64: forecast lines keep the mapping they were written with; they are write-once', async () => {
    const employee = await db().employee.findUniqueOrThrow({
      where: { employeeCode: DEMO.employee },
    });
    // A minimal snapshot as the close-week job (P2) will write it, with the mapping in effect.
    const dish = await db().dish.create({
      data: {
        factoryId: 'fac_gv',
        canteenId: 'ct_gv',
        name: 'Snapshot test',
        typeId: 'dt_fac_gv_MAN',
        sessions: ['LUNCH'],
      },
    });
    const week = await db().menuWeek.create({
      data: {
        factoryId: 'fac_gv',
        canteenId: 'ct_gv',
        weekStart: new Date('2027-06-07T00:00:00.000Z'),
      },
    });
    const day = await db().menuDay.create({
      data: { menuWeekId: week.id, date: new Date('2027-06-07T00:00:00.000Z'), isServing: true },
    });
    const item = await db().menuItem.create({
      data: { menuDayId: day.id, dishId: dish.id, sortOrder: 0 },
    });
    const line = await db().forecastLine.create({
      data: {
        menuWeekId: week.id,
        employeeId: employee.id,
        date: day.date,
        session: 'LUNCH',
        menuItemId: item.id,
        quantity: 2,
        mealUnitId: 'ou_XCT-A1',
        floorId: 'fl_T2',
        canteenId: 'ct_gv',
        costCenterId: 'cc_CC03',
      },
    });

    // Change the mapping the line was taken under, through the screen and through an import.
    await hc.patch('/api/admin/org/units/ou_XCT-A1').send({ floorId: 'fl_T4' }).expect(200);
    const preview = (await upload(hc, await xlsx([orgRow('XCT', 'T1', null, 'CC04')])).expect(201))
      .body as MappingImportView;
    await hc.post(`/api/admin/org/mapping/imports/${preview.id}/apply`).expect(200);
    await hc
      .put(`/api/employees/${DEMO.employee}/floor-override`)
      .send({ floorId: 'fl_T1', reason: 'Ngồi ở tầng 1' })
      .expect(200);

    expect(await db().forecastLine.findUniqueOrThrow({ where: { id: line.id } })).toEqual(line);
    // Rule 7: the database itself refuses to change or delete a snapshot row.
    await expect(
      db().forecastLine.update({ where: { id: line.id }, data: { floorId: 'fl_T4' } }),
    ).rejects.toThrow(/write-once/);
    await expect(db().forecastLine.delete({ where: { id: line.id } })).rejects.toThrow(
      /write-once/,
    );

    // Put the seed mapping back for the other test files.
    await hc.patch('/api/admin/org/units/ou_XCT-A1').send({ floorId: null }).expect(200);
    await db().orgUnit.update({
      where: { id: 'ou_XCT' },
      data: { floorId: 'fl_T2', costCenterId: 'cc_CC03' },
    });
    await hc
      .put(`/api/employees/${DEMO.employee}/floor-override`)
      .send({ floorId: null, reason: 'Về lại tầng của đơn vị' })
      .expect(200);
  });
});

describe('org and employee sync (plan section 5, BR-60, BR-61)', () => {
  const services = () =>
    [
      app.get(PrismaService),
      app.get(ClockService),
      app.get(AuditService),
      app.get(SystemAdminBootstrap),
      app.get(SettingsService),
    ] as const;
  const syncWith = (dmp: DmpClient) => new OrgSyncService(...services(), dmp);

  class ExtendedDmp extends MockDmpClient {
    constructor(
      private readonly extraNodes: DmpOrgNode[],
      private readonly extraEmployees: DmpEmployee[],
      private readonly rename: Record<string, string> = {},
    ) {
      super('on-time');
    }
    override async getOrgTree() {
      const nodes = (await super.getOrgTree()).map((n) => ({
        ...n,
        name: this.rename[n.code] ?? n.name,
      }));
      return [...nodes, ...this.extraNodes];
    }
    override async getEmployees() {
      return [...(await super.getEmployees()), ...this.extraEmployees];
    }
  }

  afterAll(async () => {
    await db().employee.deleteMany({ where: { employeeCode: 'PNJP999001' } });
    await db().orgUnit.deleteMany({ where: { dmpCode: { in: ['TEST-NEW'] } } });
    await db().costCenter.deleteMany({ where: { code: 'CC-DMP-NEW' } });
  });

  it('a manual sync against the mock is idempotent on the seeded data and writes a SyncRun', async () => {
    const res = await hc.post('/api/admin/org/sync').expect(200);
    expect(res.body.status).toBe('SUCCESS');
    expect(res.body.details).toMatchObject({
      orgUnits: { created: 0, updated: 0, deactivated: 0 },
      employees: { created: 0, updated: 0, total: 1700 },
      mealUnitsRecomputed: 0,
      skippedEmployees: [],
    });
    const run = await db().syncRun.findUniqueOrThrow({ where: { id: res.body.syncRunId } });
    expect(run).toMatchObject({
      type: 'ORG_EMPLOYEES',
      status: 'SUCCESS',
      recordCount: 1700 + (await db().orgUnit.count()),
    });
    const audit = await db().auditLog.findFirst({ where: { entityId: run.id } });
    expect(audit?.action).toBe('sync.orgEmployees.triggered');
  });

  it('BR-61 / BR-60: new nodes get a level and path, renames apply, DMP cost centers are created', async () => {
    const dmp = new ExtendedDmp(
      [
        {
          code: 'TEST-NEW',
          name: 'Phòng Mới',
          parentCode: 'KVP',
          levelName: 'Phòng/Xưởng',
          costCenterCode: null,
        },
      ],
      [
        {
          employeeCode: 'PNJP999001',
          name: 'Nhân viên mới',
          email: 'moi@pnjp.test',
          orgUnitCode: 'TEST-NEW',
          costCenterCode: 'CC-DMP-NEW',
          startDate: '2027-01-04',
          endDate: null,
        },
        {
          employeeCode: 'PNJP999002',
          name: 'Không rõ đơn vị',
          email: null,
          orgUnitCode: 'NOPE',
          costCenterCode: null,
          startDate: null,
          endDate: null,
        },
      ],
      { IT: 'Phòng Công nghệ' },
    );
    const result = await syncWith(dmp).run('fac_gv', null);
    expect(result.status).toBe('SUCCESS');
    expect(result.details).toMatchObject({
      orgUnits: { created: 1, updated: 1 },
      employees: { created: 1 },
      costCentersCreated: 1,
      skippedEmployees: [{ employeeCode: 'PNJP999002', orgUnitCode: 'NOPE' }],
    });
    const node = await db().orgUnit.findFirstOrThrow({ where: { dmpCode: 'TEST-NEW' } });
    expect(node).toMatchObject({ level: 3, parentId: 'ou_KVP', isMealUnit: false });
    expect(node.path).toBe(`/ou_PNJP-GV/ou_KVP/${node.id}/`);
    expect((await db().orgUnit.findUniqueOrThrow({ where: { id: 'ou_IT' } })).name).toBe(
      'Phòng Công nghệ',
    );
    const newcomer = await db().employee.findUniqueOrThrow({
      where: { employeeCode: 'PNJP999001' },
    });
    expect(newcomer).toMatchObject({ mealUnitId: null, pinHash: null, status: 'ACTIVE' });
    expect(newcomer.dmpCostCenterCode).toBe('CC-DMP-NEW');
    const cc = await db().costCenter.findFirstOrThrow({ where: { code: 'CC-DMP-NEW' } });
    expect(cc.source).toBe('DMP');
  });

  it('BR-61: a 7-level tree fails the run and writes nothing', async () => {
    const chain: DmpOrgNode[] = ['D1', 'D2', 'D3'].map((code, i, all) => ({
      code,
      name: code,
      parentCode: i === 0 ? 'XCT-A1-1' : all[i - 1]!,
      levelName: 'Sâu',
      costCenterCode: null,
    }));
    const units = await db().orgUnit.count();
    const result = await syncWith(new ExtendedDmp(chain, [])).run('fac_gv', null);
    expect(result.status).toBe('FAILED');
    expect(result.error).toContain('TOO_DEEP');
    expect(await db().orgUnit.count()).toBe(units);
    const run = await db().syncRun.findUniqueOrThrow({ where: { id: result.syncRunId } });
    expect(run.status).toBe('FAILED');
  });

  it('a DMP error fails the run; the manual trigger answers 502', async () => {
    const failing = new ExtendedDmp([], []);
    failing.getOrgTree = async () => {
      throw new DmpError('API_ERROR', 'DMP is down');
    };
    const result = await syncWith(failing).run('fac_gv', null);
    expect(result).toMatchObject({ status: 'FAILED', error: 'DMP is down' });
  });

  it('nodes gone from DMP are deactivated, not deleted; the job restores the seed names', async () => {
    await app.get(JobsService).syncOrgEmployees();
    const node = await db().orgUnit.findFirstOrThrow({ where: { dmpCode: 'TEST-NEW' } });
    expect(node.active).toBe(false);
    expect((await db().orgUnit.findUniqueOrThrow({ where: { id: 'ou_IT' } })).name).toBe(
      'Phòng IT',
    );
    const last = await db().syncRun.findFirstOrThrow({ orderBy: { startedAt: 'desc' } });
    expect(last.status).toBe('SUCCESS');
  });
});

describe('403 on every org, master and sync endpoint', () => {
  const endpoints: Endpoint[] = [
    { method: 'get', url: '/api/admin/org/tree' },
    { method: 'patch', url: '/api/admin/org/units/ou_XCT', body: { isMealUnit: true } },
    {
      method: 'post',
      url: '/api/admin/org/units/ou_XCT/dept-admins',
      body: { employeeCode: 'PNJP000001' },
    },
    { method: 'post', url: '/api/admin/org/dept-admins/x/revoke' },
    { method: 'get', url: '/api/admin/org/unassigned' },
    { method: 'get', url: '/api/admin/org/missing-cost-center' },
    { method: 'get', url: '/api/admin/org/mapping/export' },
    { method: 'post', url: '/api/admin/org/mapping/imports' },
    { method: 'get', url: '/api/admin/org/mapping/imports/x' },
    { method: 'post', url: '/api/admin/org/mapping/imports/x/apply' },
    { method: 'post', url: '/api/admin/org/sync' },
    { method: 'get', url: '/api/admin/master/canteens' },
    { method: 'post', url: '/api/admin/master/canteens', body: { code: 'X', name: 'X' } },
    { method: 'patch', url: '/api/admin/master/canteens/ct_gv', body: { name: 'X' } },
    { method: 'get', url: '/api/admin/master/floors' },
    { method: 'post', url: '/api/admin/master/floors', body: { code: 'X', name: 'X' } },
    { method: 'patch', url: '/api/admin/master/floors/fl_T1', body: { name: 'X' } },
    { method: 'get', url: '/api/admin/master/cost-centers' },
    { method: 'post', url: '/api/admin/master/cost-centers', body: { code: 'X', name: 'X' } },
    { method: 'patch', url: '/api/admin/master/cost-centers/cc_CC01', body: { name: 'X' } },
    {
      method: 'put',
      url: `/api/employees/${DEMO.employee}/floor-override`,
      body: { floorId: null, reason: 'x' },
    },
  ];

  it('an employee and a dept admin (even for a node in their own scope) get 403', async () => {
    await expectForbidden([employee, deptProd], endpoints);
  });

  it('the system admin may manage org and master data (plan section 3 matrix)', async () => {
    await sys.get('/api/admin/org/tree').expect(200);
    await sys.get('/api/admin/master/floors').expect(200);
  });
});
