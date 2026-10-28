import type { INestApplication } from '@nestjs/common';
import { SETTING_KEYS } from '@meal/shared';
import { JobsService } from '../src/jobs/jobs.service';
import {
  DEMO,
  closeDb,
  createTestApp,
  db,
  expectForbidden,
  setVnTime,
  signInReady,
  type Agent,
  type Endpoint,
} from './helpers';

let app: INestApplication;
let hc: Agent;
let sys: Agent;
let employee: Agent;
let deptProd: Agent;

async function signInAll() {
  [hc, sys, employee, deptProd] = await Promise.all([
    signInReady(app, DEMO.hcAdmin),
    signInReady(app, DEMO.sysAdmin),
    signInReady(app, 'PNJP000100'),
    signInReady(app, DEMO.deptAdminProduction),
  ]);
}

beforeAll(async () => {
  app = await createTestApp();
  await signInAll();
});
afterAll(async () => {
  // The seed spec counts floors and cost centers.
  await db().floor.deleteMany({ where: { code: 'T9-TEST' } });
  await db().costCenter.deleteMany({ where: { code: 'CC-TEST' } });
  await app.close();
  await closeDb();
});

describe('/admin/settings: plan 6.4 keys', () => {
  afterAll(async () => {
    await db().setting.update({
      where: { factoryId_key: { factoryId: 'fac_gv', key: 'registration.latePublishHours' } },
      data: { value: 24 },
    });
  });

  it('lists every key with its value and default', async () => {
    const res = await hc.get('/api/admin/settings').expect(200);
    expect(res.body.items.map((s: { key: string }) => s.key)).toEqual(SETTING_KEYS);
    expect(
      res.body.items.find((s: { key: string }) => s.key === 'registration.close'),
    ).toMatchObject({
      value: { weekday: 4, time: '23:59' },
      defaultValue: { weekday: 4, time: '23:59' },
    });
  });

  it('validates each value against its key, stores it and audits before / after', async () => {
    await hc
      .put('/api/admin/settings/registration.latePublishHours')
      .send({ value: 0 })
      .expect(400);
    await hc.put('/api/admin/settings/day.lockTime').send({ value: '8h' }).expect(400);
    await hc.put('/api/admin/settings/not.a.key').send({ value: 1 }).expect(404);
    const res = await sys
      .put('/api/admin/settings/registration.latePublishHours')
      .send({ value: 12 })
      .expect(200);
    expect(res.body.value).toBe(12);
    const audit = await db().auditLog.findFirstOrThrow({
      where: { action: 'setting.updated', entityId: 'fac_gv:registration.latePublishHours' },
      orderBy: { at: 'desc' },
    });
    expect([audit.before, audit.after]).toEqual([24, 12]);
  });

  it('caterer name and unit price are business settings too', async () => {
    const before = (await hc.get('/api/admin/supplier').expect(200)).body;
    const res = await hc
      .put('/api/admin/supplier')
      .send({ name: before.name, unitPrice: 36000 })
      .expect(200);
    expect(res.body.unitPrice).toBe(36000);
    await hc.put('/api/admin/supplier').send({ name: before.name, unitPrice: -1 }).expect(400);
    await hc.put('/api/admin/supplier').send(before).expect(200);
  });
});

describe('/admin/master: canteens, floors, cost centers (BR-62)', () => {
  it('BR-62: adds, edits and deactivates; codes are unique; nothing is deleted', async () => {
    const created = await hc
      .post('/api/admin/master/floors')
      .send({ code: 'T9-TEST', name: 'Tầng thử', sortOrder: 9 })
      .expect(201);
    await hc.post('/api/admin/master/floors').send({ code: 'T9-TEST', name: 'Trùng' }).expect(409);
    await hc
      .patch(`/api/admin/master/floors/${created.body.id}`)
      .send({ name: 'Tầng thử 2', active: false })
      .expect(200);
    const floors = (await hc.get('/api/admin/master/floors').expect(200)).body.items;
    expect(floors.find((f: { code: string }) => f.code === 'T9-TEST')).toMatchObject({
      name: 'Tầng thử 2',
      active: false,
      usageCount: 0,
    });
    const audits = await db().auditLog.findMany({ where: { entityId: created.body.id } });
    expect(audits.map((a) => a.action).sort()).toEqual(['floor.created', 'floor.updated']);
  });

  it('BR-62: an item still mapped to org nodes cannot be deactivated', async () => {
    const res = await hc
      .patch('/api/admin/master/floors/fl_T1')
      .send({ active: false })
      .expect(409);
    expect(res.body.code).toBe('IN_USE');
    const cc = await hc
      .post('/api/admin/master/cost-centers')
      .send({ code: 'CC-TEST', name: 'Thử' })
      .expect(201);
    const list = (await hc.get('/api/admin/master/cost-centers').expect(200)).body.items;
    expect(list.find((c: { id: string }) => c.id === cc.body.id).source).toBe('MANUAL');
    await hc.post('/api/admin/master/canteens').send({ code: 'bad code!', name: 'X' }).expect(400);
  });
});

describe('/admin/employees: finder and cost-center override (BR-63)', () => {
  it('BR-63: filters by resolved floor and meal status, and shows the cost-center source', async () => {
    const t3 = await hc.get('/api/employees?floorId=fl_T3&limit=100').expect(200);
    expect(t3.body.total).toBeGreaterThan(0);
    expect(t3.body.items.every((e: { floorId: string }) => e.floorId === 'fl_T3')).toBe(true);
    const unassigned = await hc.get('/api/employees?mealStatus=NO_MEAL_UNIT&limit=100').expect(200);
    expect(
      unassigned.body.items.some(
        (e: { employeeCode: string }) => e.employeeCode === DEMO.unassigned,
      ),
    ).toBe(true);
    const byNode = await hc.get('/api/employees?orgUnitId=ou_XCT-A1&limit=100').expect(200);
    expect(byNode.body.items.every((e: { orgPath: string }) => e.orgPath.includes('Tổ Nhẫn'))).toBe(
      true,
    );
  });

  it('BR-63: cost center follows the org tree; the HC admin sets a pick-up floor with a reason, audited', async () => {
    // Xưởng Kim hoàn: org.e2e-spec changes the production mapping while this file runs.
    const OTHER = 'PNJP000868';
    const find = async () => (await hc.get(`/api/employees?q=${OTHER}`).expect(200)).body.items[0];
    const before = await find();
    expect(before).toMatchObject({ costCenterSource: 'MAPPING', floorSource: 'MEAL_UNIT' });
    await hc.put(`/api/employees/${OTHER}/floor-override`).send({ floorId: 'fl_T1' }).expect(400); // a reason is required
    await hc
      .put(`/api/employees/${OTHER}/floor-override`)
      .send({ floorId: 'fl_T1', reason: 'Ngồi ở tầng 1' })
      .expect(200);
    expect(await find()).toMatchObject({
      floorId: 'fl_T1',
      floorSource: 'OVERRIDE',
      floorOverrideId: 'fl_T1',
      costCenterId: before.costCenterId, // the pick-up floor never moves the cost
    });
    // Filtering by floor finds the moved employee there.
    const onT1 = await hc.get(`/api/employees?floorId=fl_T1&q=${OTHER}`).expect(200);
    expect(onT1.body.total).toBe(1);
    await hc
      .put(`/api/employees/${OTHER}/floor-override`)
      .send({ floorId: null, reason: 'Về lại tầng của đơn vị' })
      .expect(200);
    expect(await find()).toMatchObject({ floorId: before.floorId, floorSource: 'MEAL_UNIT' });
    const audits = await db().auditLog.findMany({
      where: {
        action: 'employee.floorOverride.set',
        reason: 'Ngồi ở tầng 1',
        entityId: (await db().employee.findUniqueOrThrow({ where: { employeeCode: OTHER } })).id,
      },
    });
    expect(audits).toHaveLength(1);
    expect(audits[0]!.after).toEqual({ floorCode: 'T1' });
  });

  it('Khối / Trung tâm / Phòng ban filter options follow the scope', async () => {
    const all = (await hc.get('/api/employees/org-units').expect(200)).body.items;
    expect(all[0]).toMatchObject({ level: 1, parentId: null });
    expect(all.some((o: { levelName: string }) => o.levelName === 'Khối')).toBe(true);
    const mine = (await deptProd.get('/api/employees/org-units').expect(200)).body.items;
    expect(mine.length).toBeLessThan(all.length);
    expect(mine[0]).toMatchObject({ id: 'ou_XCT' });
  });

  it('a dept admin finds employees in scope only and may not set overrides', async () => {
    const res = await deptProd.get('/api/employees?limit=100').expect(200);
    expect(
      res.body.items.every((e: { orgPath: string }) => e.orgPath.includes('Xưởng Chế tác')),
    ).toBe(true);
    await deptProd
      .put(`/api/employees/${DEMO.employee}/floor-override`)
      .send({ floorId: null, reason: 'x' })
      .expect(403);
  });
});

describe('/system: HC and system admins are assigned by system admins only', () => {
  it('assigns, lists and revokes an HC admin; the last system admin cannot be revoked', async () => {
    const res = await sys
      .post('/api/system/roles')
      .send({ employeeCode: 'PNJP000110', role: 'HC_ADMIN' })
      .expect(201);
    const list = (await sys.get('/api/system/roles').expect(200)).body.items;
    expect(list.some((r: { id: string }) => r.id === res.body.id)).toBe(true);
    const newHc = await signInReady(app, 'PNJP000110');
    await newHc.get('/api/admin/settings').expect(200);
    await sys.post(`/api/system/roles/${res.body.id}/revoke`).expect(200);
    await newHc.get('/api/admin/settings').expect(403);

    const sysRows = list.filter((r: { role: string }) => r.role === 'SYS_ADMIN');
    expect(sysRows).toHaveLength(1);
    const last = await sys.post(`/api/system/roles/${sysRows[0].id}/revoke`).expect(409);
    expect(last.body.code).toBe('INVALID_STATE');
  });
});

describe('BR-06: "menu not published" warning', () => {
  // Week 2028-01-10 has no menu; its warning starts Monday 2028-01-03 12:00.
  it('BR-06: the HC admin sees it on every screen from Monday 12:00; employees never do', async () => {
    setVnTime(app, '2028-01-03', '11:59');
    await signInAll();
    const early = await hc.get('/api/me/banners').expect(200);
    expect(
      early.body.items.filter((b: { kind: string }) => b.kind === 'MENU_NOT_PUBLISHED'),
    ).toEqual([]);

    setVnTime(app, '2028-01-03', '12:00');
    const due = await hc.get('/api/me/banners').expect(200);
    expect(due.body.items).toContainEqual({
      id: 'menu-not-published:ct_gv:2028-01-10',
      severity: 'warning',
      kind: 'MENU_NOT_PUBLISHED',
      params: { canteen: 'Nhà ăn Gò Vấp', weekStart: '2028-01-10' },
    });
    const emp = await employee.get('/api/me/banners').expect(200);
    expect(emp.body.items.some((b: { kind: string }) => b.kind === 'MENU_NOT_PUBLISHED')).toBe(
      false,
    );
    expect(await app.get(JobsService).menuPublishWarning()).toBe(1);
  });

  it('BR-06: the warning reads menu.publishWarnAt from settings', async () => {
    await hc
      .put('/api/admin/settings/menu.publishWarnAt')
      .send({ value: { weekday: 2, time: '09:00' } })
      .expect(200);
    const res = await hc.get('/api/me/banners').expect(200); // still Monday 12:00
    expect(res.body.items.some((b: { kind: string }) => b.kind === 'MENU_NOT_PUBLISHED')).toBe(
      false,
    );
    await hc
      .put('/api/admin/settings/menu.publishWarnAt')
      .send({ value: { weekday: 1, time: '12:00' } })
      .expect(200);
  });
});

describe('403 on every settings, supplier and system endpoint', () => {
  const endpoints: Endpoint[] = [
    { method: 'get', url: '/api/admin/settings' },
    { method: 'put', url: '/api/admin/settings/day.lockTime', body: { value: '08:00' } },
    { method: 'get', url: '/api/admin/supplier' },
    { method: 'put', url: '/api/admin/supplier', body: { name: 'X', unitPrice: 1 } },
  ];
  const systemOnly: Endpoint[] = [
    { method: 'get', url: '/api/system/roles' },
    {
      method: 'post',
      url: '/api/system/roles',
      body: { employeeCode: 'PNJP000001', role: 'HC_ADMIN' },
    },
    { method: 'post', url: '/api/system/roles/x/revoke' },
  ];

  it('an employee and a dept admin get 403', async () => {
    await expectForbidden([employee, deptProd], [...endpoints, ...systemOnly]);
  });

  it('the HC admin gets 403 on role assignment (system admins only)', async () => {
    await expectForbidden([hc], systemOnly);
  });
});
