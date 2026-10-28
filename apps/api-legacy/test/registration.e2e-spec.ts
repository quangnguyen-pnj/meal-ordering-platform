import type { INestApplication } from '@nestjs/common';
import ExcelJS from 'exceljs';
import {
  addDays,
  type MyDayRegistrationView,
  type MyRegistrationWeekView,
  type RegistrationGridView,
  type RegistrationImportView,
} from '@meal/shared';
import { MockDmpClient } from '../src/dmp/mock/mock-dmp.client';
import { JobsService } from '../src/jobs/jobs.service';
import { OrgSyncService } from '../src/org/org-sync.service';
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
import { AuditService } from '../src/audit/audit.service';
import { ClockService } from '../src/clock/clock.service';
import { PrismaService } from '../src/prisma/prisma.service';
import { SystemAdminBootstrap } from '../src/rbac/system-admin-bootstrap.service';
import { SettingsService } from '../src/settings/settings.service';

/**
 * P2 exit criteria with fake time, plus BR-14 (lock-time cancellation), BR-20 to BR-27, BR-36 and
 * a 403 for every new admin endpoint.
 *
 * Week N starts Monday 2027-10-04. Its regular closeAt is Friday 2027-10-01 00:00 Vietnam time
 * (shown as Thursday 23:59). Week N+1 (2027-10-11) proves the self-healing snapshot; week N+2
 * (2027-10-18) holds the 50 × 5 bulk-grid entry.
 */
const N = '2027-10-04';
const N1 = addDays(N, 7);
const N2 = addDays(N, 14);
const PREV = addDays(N, -7); // week N-1
const MON = N;
const TUE = addDays(N, 1);
const WED = addDays(N, 2);
const THU = addDays(N, 3);
const FRI = addDays(N, 4);
const UNIT = 'ou_XCT-A1-1'; // Nhóm Nhẫn 1, in Tổ Nhẫn (meal unit), dept admin production's scope

let app: INestApplication;
let hc: Agent;
let a: Agent; // DEMO.employee: registers before closeAt
let b: Agent; // a colleague with no forecast
let c: Agent; // a colleague who leaves (BR-36)
let dept: Agent; // dept admin of Xưởng Chế tác
let deptOther: Agent; // dept admin of Xưởng Kim hoàn: out of scope
let unassigned: Agent;
let sys: Agent;
let B = '';
let C = '';

const DISHES = {
  chay: ['Đậu hũ kho nấm', 'CHAY'],
  chao: ['Cháo thịt bằm', 'CHAO'],
  ca: ['Cá basa kho', 'MAN'],
  thit: ['Thịt luộc mắm nêm', 'MAN'],
} as const;
type DishKey = keyof typeof DISHES;
const dishId: Partial<Record<DishKey, string>> = {};
let dessertId = '';
let gaId = ''; // not on any menu at first; replaces a dish after closeAt (BR-14)

async function signInAll() {
  [hc, a, b, c, dept, deptOther, unassigned, sys] = await Promise.all([
    signInReady(app, DEMO.hcAdmin),
    signInReady(app, DEMO.employee),
    signInReady(app, B),
    signInReady(app, C),
    signInReady(app, DEMO.deptAdminProduction),
    signInReady(app, DEMO.deptAdminJewelry),
    signInReady(app, DEMO.unassigned),
    signInReady(app, DEMO.sysAdmin),
  ]);
}

/** Moves the fake clock and signs everyone in again (sessions age with fake time). */
async function at(date: string, time: string) {
  setVnTime(app, date, time);
  await signInAll();
}

async function publishWeek(weekStart: string) {
  const days = [0, 1, 2, 3, 4].map((i) => ({
    date: addDays(weekStart, i),
    isServing: true,
    items: (Object.keys(DISHES) as DishKey[]).map((k) => ({ dishId: dishId[k]! })),
    dessertIds: [dessertId],
  }));
  const saved = await hc
    .put('/api/admin/menu/weeks')
    .send({ canteenId: 'ct_gv', weekStart, days })
    .expect(200);
  await hc.post(`/api/admin/menu/weeks/${saved.body.menu.id}/publish`).expect(200);
}

async function myWeek(agent: Agent, weekStart: string) {
  const res = await agent.get(`/api/registrations/me/week?weekStart=${weekStart}`).expect(200);
  return res.body as MyRegistrationWeekView;
}

/** Menu item ids by week, date and dish, read once from the employee's view after publishing. */
const items: Record<string, Record<string, Partial<Record<DishKey, string>>>> = {};

async function loadItems(weekStart: string) {
  const view = await myWeek(a, weekStart);
  if (view.state !== 'PUBLISHED') throw new Error(`week ${weekStart} is ${view.state}`);
  items[weekStart] = {};
  for (const day of view.days) {
    items[weekStart][day.date] = Object.fromEntries(
      (Object.keys(DISHES) as DishKey[]).map((k) => [
        k,
        day.items.find((i) => i.displayName.endsWith(DISHES[k][0]))!.menuItemId,
      ]),
    );
  }
}

const line = (weekStart: string, date: string, key: DishKey, quantity: number) => ({
  menuItemId: items[weekStart]![date]![key]!,
  quantity,
});

async function dayOf(agent: Agent, date: string) {
  const res = await agent.get(`/api/registrations/me/day?date=${date}`).expect(200);
  const view = res.body as MyDayRegistrationView;
  if (view.state !== 'DAY') throw new Error(`day ${date} is ${view.state}`);
  return view.day;
}

async function forecastSnapshot(weekStart: string) {
  return db().forecastLine.findMany({
    where: { menuWeek: { canteenId: 'ct_gv', weekStart: new Date(`${weekStart}T00:00:00Z`) } },
    orderBy: [{ employeeId: 'asc' }, { date: 'asc' }, { menuItemId: 'asc' }],
  });
}

beforeAll(async () => {
  app = await createTestApp();
  // Two plain colleagues of DEMO.employee in the same team and meal unit.
  const colleagues = await db().employee.findMany({
    where: {
      orgUnitId: UNIT,
      status: 'ACTIVE',
      employeeCode: { notIn: Object.values(DEMO) },
      roleAssignments: { none: {} },
    },
    orderBy: { employeeCode: 'asc' },
    take: 2,
  });
  [B, C] = colleagues.map((e) => e.employeeCode) as [string, string];
  await at(PREV, '10:00'); // Monday of week N-1
  const dish = (name: string, type: string) => ({
    canteenId: 'ct_gv',
    name,
    typeId: `dt_fac_gv_${type}`,
    sessions: ['LUNCH'],
  });
  for (const [key, [name, type]] of Object.entries(DISHES)) {
    const res = await hc.post('/api/admin/dishes').send(dish(name, type)).expect(201);
    dishId[key as DishKey] = res.body.id;
  }
  gaId = (await hc.post('/api/admin/dishes').send(dish('Gà kho gừng', 'MAN')).expect(201)).body.id;
  dessertId = (
    await hc.post('/api/admin/desserts').send({ canteenId: 'ct_gv', name: 'Chuối cau' }).expect(201)
  ).body.id;
});

afterAll(async () => {
  await db().employee.updateMany({ where: { employeeCode: C }, data: { endDate: null } });
  app.get(ClockService).clearFakeTime();
  await app.close();
  await closeDb();
});

describe('BR-02: nothing can be registered before the menu is published', () => {
  it('BR-02: the week reads "not published" and a save is refused', async () => {
    expect(await myWeek(a, N)).toMatchObject({ state: 'NOT_PUBLISHED' });
    await a
      .put('/api/registrations/me/week')
      .send({ weekStart: N, days: [{ date: MON, lines: [] }] })
      .expect(409);
  });

  it('BR-60: an employee without a meal unit cannot register', async () => {
    expect(await myWeek(unassigned, N)).toEqual({ state: 'UNASSIGNED', weekStart: N });
  });

  it('publishes weeks N, N+1 and N+2', async () => {
    await publishWeek(N);
    await publishWeek(N1);
    await publishWeek(N2);
    await loadItems(N);
    await loadItems(N1);
    // J1: the week open for registration is preselected.
    const res = await a.get('/api/registrations/me/week').expect(200);
    expect(res.body).toMatchObject({ state: 'PUBLISHED', weekStart: N });
  });
});

describe('P2 exit criterion: the bulk grid saves 50 employees × 5 days', () => {
  let grid: RegistrationGridView;
  let rows: RegistrationGridView['rows'];

  it('the dept admin lists their units and opens the grid of a team', async () => {
    const units = await dept.get('/api/admin/registrations/units').expect(200);
    const team = units.body.items.find((u: { id: string }) => u.id === UNIT);
    expect(team.employeeCount).toBeGreaterThanOrEqual(53);
    // Nothing outside Xưởng Chế tác is offered.
    expect(units.body.items.some((u: { id: string }) => u.id === 'ou_XKH')).toBe(false);

    grid = (
      await dept.get(`/api/admin/registrations/units/${UNIT}/grid?weekStart=${N2}`).expect(200)
    ).body as RegistrationGridView;
    expect(grid.state).toBe('PUBLISHED');
    expect(grid.canteenId).toBe('ct_gv');
    expect(grid.days.map((d) => d.window)).toEqual(Array(5).fill('ON_BEHALF'));
    rows = grid.rows
      .filter((r) => ![DEMO.employee, B, C].includes(r.employeeCode as never))
      .slice(0, 50);
    expect(rows).toHaveLength(50);
  });

  it('saves 5 days of 50 rows, one batch per day', async () => {
    const started = Date.now();
    for (const [i, day] of grid.days.entries()) {
      const [chay, ca] = [day.items[0]!.id, day.items[2]!.id];
      const res = await dept
        .put(`/api/admin/registrations/units/${UNIT}/days/${day.date}`)
        .send({
          canteenId: 'ct_gv',
          rows: rows.map((r, n) => ({
            employeeCode: r.employeeCode,
            lines: [
              { menuItemId: chay, quantity: 1 },
              { menuItemId: ca, quantity: (n + i) % 3 },
            ],
          })),
        })
        .expect(200);
      expect(res.body.changedEmployees).toBe(50);
    }
    expect(Date.now() - started).toBeLessThan(30_000);

    const after = (await dept.get(`/api/admin/registrations/units/${UNIT}/grid?weekStart=${N2}`))
      .body as RegistrationGridView;
    const saved = after.rows.filter((r) => rows.some((x) => x.employeeCode === r.employeeCode));
    expect(saved).toHaveLength(50);
    for (const row of saved) {
      for (const day of after.days)
        expect(row.cells[day.date]!.quantities[day.items[0]!.id]).toBe(1);
    }
    const count = await db().registration.count({
      where: {
        employee: { employeeCode: { in: rows.map((r) => r.employeeCode) } },
        date: { gte: new Date(`${N2}T00:00:00Z`), lte: new Date(`${addDays(N2, 4)}T00:00:00Z`) },
        quantity: { gt: 0 },
      },
    });
    // 50 × 5 Chay lines, plus the Mặn lines whose quantity was not 0.
    let man = 0;
    for (let i = 0; i < 5; i += 1) for (let n = 0; n < 50; n += 1) if ((n + i) % 3 !== 0) man += 1;
    expect(count).toBe(250 + man);
    // Every changed employee-day is audited (BR-70).
    expect(
      await db().auditLog.count({
        where: { action: 'registration.onBehalf', entityId: { endsWith: `:${N2}` } },
      }),
    ).toBe(50);
  });

  it('one invalid row rejects the whole batch with row-level errors', async () => {
    const day = grid.days[0]!;
    const snapshot = () =>
      db().registration.findMany({
        where: { date: new Date(`${day.date}T00:00:00Z`) },
        orderBy: { id: 'asc' },
      });
    const before = await snapshot();
    const outsider = await db().employee.findFirstOrThrow({ where: { orgUnitId: 'ou_XKH-DUC-2' } });
    const res = await dept
      .put(`/api/admin/registrations/units/${UNIT}/days/${day.date}`)
      .send({
        canteenId: 'ct_gv',
        rows: [
          {
            employeeCode: rows[0]!.employeeCode,
            lines: [{ menuItemId: day.items[0]!.id, quantity: 5 }],
          },
          {
            employeeCode: rows[1]!.employeeCode,
            lines: [{ menuItemId: day.items[0]!.id, quantity: -1 }],
          },
          {
            employeeCode: rows[2]!.employeeCode,
            lines: [{ menuItemId: day.items[1]!.id, quantity: 1.5 }],
          },
          { employeeCode: outsider.employeeCode, lines: [] },
        ],
      })
      .expect(400);
    expect(res.body.details).toEqual([
      {
        employeeCode: rows[1]!.employeeCode,
        code: 'QUANTITY_NEGATIVE',
        menuItemId: day.items[0]!.id,
      },
      {
        employeeCode: rows[2]!.employeeCode,
        code: 'QUANTITY_NOT_INTEGER',
        menuItemId: day.items[1]!.id,
      },
      { employeeCode: outsider.employeeCode, code: 'NOT_IN_UNIT' },
    ]);
    expect(await snapshot()).toEqual(before);
  });

  it('BR-21: progress by unit and the not-registered list', async () => {
    const progress = await dept
      .get(`/api/admin/registrations/progress?weekStart=${N2}`)
      .expect(200);
    const unit = progress.body.units.find(
      (u: { mealUnitId: string }) => u.mealUnitId === 'ou_XCT-A1',
    );
    expect(unit).toMatchObject({ registered: 50, published: true });
    expect(unit.headcount).toBe(unit.registered + unit.notRegistered);

    const list = await dept
      .get(`/api/admin/registrations/not-registered?weekStart=${N2}`)
      .expect(200);
    const codes = list.body.items.map((i: { employeeCode: string }) => i.employeeCode);
    expect(codes).toContain(DEMO.employee);
    expect(codes.some((code: string) => rows.some((r) => r.employeeCode === code))).toBe(false);
    // Dept admins only see their own units (plan section 3).
    expect(
      list.body.items.every((i: { orgPath: string }) => i.orgPath.includes('Xưởng Chế tác')),
    ).toBe(true);
    const day = await hc
      .get(`/api/admin/registrations/not-registered?weekStart=${N2}&date=${N2}`)
      .expect(200);
    expect(day.body.total).toBeGreaterThan(list.body.total);
  });
});

describe('P2 exit criterion: register, free edits, forecast at closeAt, BR-23 changes, lock at 08:00', () => {
  let forecast: Awaited<ReturnType<typeof forecastSnapshot>>;

  it('BR-22: on Tuesday of week N-1 the employee registers the week', async () => {
    await at(addDays(PREV, 1), '10:00');
    const res = await a
      .put('/api/registrations/me/week')
      .send({
        weekStart: N,
        days: [
          { date: MON, lines: [line(N, MON, 'ca', 1)] },
          { date: TUE, lines: [line(N, TUE, 'chay', 1), line(N, TUE, 'chao', 1)] },
          { date: WED, lines: [line(N, WED, 'thit', 2)] },
        ],
      })
      .expect(200);
    expect(res.body.changedDays).toEqual([MON, TUE, WED]);
    expect(res.body.view).toMatchObject({
      state: 'PUBLISHED',
      totalMeals: 5,
      forecastTaken: false,
    });
    expect(res.body.view.days.map((d: { window: string }) => d.window)).toEqual(
      Array(5).fill('OPEN'),
    );
    // C registers too, before closeAt (used by BR-36 below).
    for (const date of [WED, THU, FRI]) {
      await c
        .put(`/api/registrations/me/days/${date}`)
        .send({ lines: [line(N, date, 'ca', 1)] })
        .expect(200);
    }
  });

  it('BR-20: the API rejects negative, fractional and off-menu quantities', async () => {
    const res = await a
      .put('/api/registrations/me/week')
      .send({
        weekStart: N,
        days: [
          { date: MON, lines: [line(N, MON, 'ca', -1)] },
          { date: TUE, lines: [line(N, TUE, 'chay', 1.5)] },
          { date: WED, lines: [line(N, MON, 'chay', 1)] },
        ],
      })
      .expect(400);
    expect(res.body.details.map((d: { code: string }) => d.code)).toEqual([
      'QUANTITY_NEGATIVE',
      'QUANTITY_NOT_INTEGER',
      'ITEM_NOT_ON_MENU',
    ]);
    // No portion limit: 3 of one dish is fine (BR-20).
    await a
      .put(`/api/registrations/me/days/${THU}`)
      .send({ lines: [line(N, THU, 'ca', 3)] })
      .expect(200);
  });

  it('BR-22: free edits before closeAt are not audited as changes after close', async () => {
    await at(addDays(PREV, 2), '15:00');
    await a
      .put(`/api/registrations/me/days/${WED}`)
      .send({ lines: [line(N, WED, 'thit', 1), line(N, WED, 'ca', 1)] })
      .expect(200);
    await a
      .put(`/api/registrations/me/days/${TUE}`)
      .send({ lines: [line(N, TUE, 'chay', 1)] })
      .expect(200);
    expect(
      await db().auditLog.count({
        where: {
          action: 'registration.changedAfterClose',
          entityId: { startsWith: `${DEMO.employee}:` },
        },
      }),
    ).toBe(0);
  });

  it('BR-03: a save at Thursday 23:59:30 is still before closeAt', async () => {
    await at(addDays(PREV, 3), '23:59');
    app.get(ClockService).advanceFakeTime(30_000);
    const res = await a
      .put(`/api/registrations/me/days/${THU}`)
      .send({ lines: [line(N, THU, 'ca', 1)] })
      .expect(200);
    expect(res.body.view.day.window).toBe('OPEN');
  });

  it('BR-07: at Friday 00:00 the close-week job takes the forecast snapshot, once', async () => {
    await at(addDays(PREV, 4), '00:00');
    const jobs = app.get(JobsService);
    expect(await jobs.closeWeek()).toBeGreaterThanOrEqual(1);
    expect(await jobs.closeWeek()).toBe(0);

    const week = await db().menuWeek.findFirstOrThrow({
      where: { canteenId: 'ct_gv', weekStart: new Date(`${N}T00:00:00Z`) },
    });
    expect(week.status).toBe('CLOSED');
    forecast = await forecastSnapshot(N);
    const employeeA = await db().employee.findUniqueOrThrow({
      where: { employeeCode: DEMO.employee },
    });
    const mine = forecast.filter((l) => l.employeeId === employeeA.id);
    // Mon 1 Cá, Tue 1 Chay, Wed 1 Thịt + 1 Cá, Thu 1 Cá.
    expect(mine.map((l) => [l.date.toISOString().slice(0, 10), l.quantity])).toEqual(
      expect.arrayContaining([
        [MON, 1],
        [TUE, 1],
        [WED, 1],
        [WED, 1],
        [THU, 1],
      ]),
    );
    expect(mine).toHaveLength(5);
    // BR-64: the lines carry the mapping in effect at closeAt.
    expect(mine[0]).toMatchObject({
      mealUnitId: 'ou_XCT-A1',
      floorId: 'fl_T2',
      canteenId: 'ct_gv',
    });
    const employeeB = await db().employee.findUniqueOrThrow({ where: { employeeCode: B } });
    expect(forecast.some((l) => l.employeeId === employeeB.id)).toBe(false);
    expect(
      await db().auditLog.count({ where: { action: 'forecast.snapshot', entityId: week.id } }),
    ).toBe(1);
  });

  it('BR-23: after closeAt the employee adds a meal for Monday; it is audited and differs from the forecast', async () => {
    await at(addDays(PREV, 5), '10:00');
    const res = await a
      .put(`/api/registrations/me/days/${MON}`)
      .send({ lines: [line(N, MON, 'ca', 1), line(N, MON, 'chay', 1)] })
      .expect(200);
    expect(res.body.view.day).toMatchObject({
      window: 'AFTER_CLOSE',
      forecastTotal: 1,
      actualTotal: 2,
      changed: true,
    });
    const audit = await db().auditLog.findFirstOrThrow({
      where: { action: 'registration.changedAfterClose', entityId: `${DEMO.employee}:${MON}` },
    });
    expect(audit.after).toMatchObject({ quantities: expect.any(Object) });
  });

  it('BR-23: an employee with no forecast registers for Monday at 07:59 on day D', async () => {
    await at(MON, '07:59');
    const res = await b
      .put(`/api/registrations/me/days/${MON}`)
      .send({ lines: [line(N, MON, 'thit', 1)] })
      .expect(200);
    expect(res.body.view.day).toMatchObject({
      window: 'AFTER_CLOSE',
      forecastTotal: 0,
      actualTotal: 1,
      changed: true,
    });
  });

  it('BR-24: at 08:00 on day D employees cannot change anything; later days stay open', async () => {
    await at(MON, '08:00');
    const locked = await a.put(`/api/registrations/me/days/${MON}`).send({ lines: [] }).expect(409);
    expect(locked.body).toMatchObject({ code: 'DAY_LOCKED', details: { date: MON } });
    await b.put(`/api/registrations/me/days/${MON}`).send({ lines: [] }).expect(409);
    expect((await dayOf(a, MON)).window).toBe('LOCKED');
    // A week save that leaves the locked day as it is still goes through.
    await a
      .put('/api/registrations/me/week')
      .send({
        weekStart: N,
        days: [
          { date: MON, lines: [line(N, MON, 'ca', 1), line(N, MON, 'chay', 1)] },
          { date: FRI, lines: [line(N, FRI, 'chao', 1)] },
        ],
      })
      .expect(200);
    // Admins cannot register on behalf after the lock either (BR-21).
    const day = (await dept.get(`/api/admin/registrations/units/${UNIT}/grid?weekStart=${N}`)).body
      .days[0];
    expect(day.window).toBe('EXTRA');
    await dept
      .put(`/api/admin/registrations/units/${UNIT}/days/${MON}`)
      .send({ canteenId: 'ct_gv', rows: [{ employeeCode: B, lines: [] }] })
      .expect(409);
  });

  it('both changes show as differences from the forecast, in the employee views and the grid', async () => {
    expect(await dayOf(a, MON)).toMatchObject({ changed: true, forecastTotal: 1, actualTotal: 2 });
    expect(await dayOf(b, MON)).toMatchObject({ changed: true, forecastTotal: 0, actualTotal: 1 });
    const grid = (await dept.get(`/api/admin/registrations/units/${UNIT}/grid?weekStart=${N}`))
      .body as RegistrationGridView;
    expect(grid.forecastTaken).toBe(true);
    const cell = (code: string) => grid.rows.find((r) => r.employeeCode === code)!.cells[MON]!;
    const sum = (q: Record<string, number> | null) =>
      Object.values(q ?? {}).reduce((s, n) => s + n, 0);
    expect([sum(cell(DEMO.employee).forecast), sum(cell(DEMO.employee).quantities)]).toEqual([
      1, 2,
    ]);
    expect([sum(cell(B).forecast), sum(cell(B).quantities)]).toEqual([0, 1]);
  });

  it('BR-25: after 08:00 an admin adds an Extra meal with a reason', async () => {
    await at(MON, '08:05');
    const chao = line(N, MON, 'chao', 1);
    const url = `/api/admin/registrations/employees/${DEMO.employee}/extras`;
    await dept.post(url).send({ date: MON, menuItemId: chao.menuItemId, quantity: 1 }).expect(400);
    await dept
      .post(url)
      .send({ date: MON, menuItemId: chao.menuItemId, quantity: 1, reason: ' ' })
      .expect(400);
    const res = await dept
      .post(url)
      .send({ date: MON, menuItemId: chao.menuItemId, quantity: 1, reason: 'Quên đăng ký' })
      .expect(200);
    expect(res.body).toMatchObject({ before: 0, after: 1 });
    expect(await dayOf(a, MON)).toMatchObject({ extra: true, actualTotal: 3 });
    const audit = await db().auditLog.findFirstOrThrow({
      where: { action: 'registration.extraAdded', entityId: `${DEMO.employee}:${MON}` },
    });
    expect(audit.reason).toBe('Quên đăng ký');
    // Before a day's lock, extras are not the way: register on behalf (BR-21).
    await dept
      .post(url)
      .send({
        date: TUE,
        menuItemId: line(N, TUE, 'chao', 1).menuItemId,
        quantity: 1,
        reason: 'x',
      })
      .expect(409);
  });

  it('BR-27: colleagues see name, dish, quantity and eating only', async () => {
    const res = await b.get('/api/team').expect(200);
    expect(res.body.unitId).toBe('ou_XCT-A1');
    const employeeA = await db().employee.findUniqueOrThrow({
      where: { employeeCode: DEMO.employee },
    });
    const an = res.body.members.find((m: { key: string }) => m.key === employeeA.id);
    expect(Object.keys(an).sort()).toEqual(['eating', 'key', 'meals', 'name']);
    expect(an.eating).toBe(true);
    expect(an.meals.reduce((s: number, m: { quantity: number }) => s + m.quantity, 0)).toBe(3);
    const search = await b.get(`/api/team?q=${DEMO.employee}`).expect(200);
    expect(search.body.members.length).toBeLessThan(res.body.members.length);
    expect(search.body.members.some((m: { key: string }) => m.key === employeeA.id)).toBe(true);
  });

  it('BR-14: a removed dish waits for reselection, and is cancelled at that day’s lock', async () => {
    await at(TUE, '10:00');
    const menu = await hc.get(`/api/admin/menu/weeks?canteenId=ct_gv&weekStart=${N}`).expect(200);
    const days = menu.body.days
      .filter((d: { isServing: boolean }) => d.isServing)
      .map((d: { date: string; items: Array<{ id: string; dishId: string }> }) => ({
        date: d.date,
        isServing: true,
        items: d.items
          .filter((i) => d.date !== WED || i.dishId !== dishId.thit)
          .map((i) => ({ id: i.id, dishId: i.dishId })),
        dessertIds: [dessertId],
      }));
    const saved = await hc
      .put('/api/admin/menu/weeks')
      .send({ canteenId: 'ct_gv', weekStart: N, days, reason: 'NCC đổi món' })
      .expect(200);
    expect(saved.body.effects.flaggedRegistrations).toBe(1);
    expect((await dayOf(a, WED)).needsReselect).toEqual([
      { displayName: '[Mặn] Thịt luộc mắm nêm', quantity: 1 },
    ]);
    // The employee was told in the app; dismissing the notice leaves no trace for other tests.
    const banners = (await a.get('/api/me/banners').expect(200)).body.items as Array<{
      notificationId?: string;
      notificationType?: string;
      params: Record<string, string>;
    }>;
    const notice = banners.find(
      (n) => n.notificationType === 'MENU_DISH_REMOVED' && n.params.date === WED,
    )!;
    expect(notice.params.dish).toBe('[Mặn] Thịt luộc mắm nêm');
    await a.post(`/api/me/notifications/${notice.notificationId}/read`).expect(200);

    const pending = async () =>
      (
        await db().registration.findFirstOrThrow({
          where: {
            employee: { employeeCode: DEMO.employee },
            date: new Date(`${WED}T00:00:00Z`),
            menuItem: { dishId: dishId.thit },
          },
        })
      ).status;
    const jobs = app.get(JobsService);
    setVnTime(app, WED, '07:59');
    await jobs.reselectDeadline();
    expect(await pending()).toBe('NEEDS_RESELECT');
    setVnTime(app, WED, '08:00');
    expect(await jobs.reselectDeadline()).toBeGreaterThanOrEqual(1);
    expect(await jobs.reselectDeadline()).toBe(0);
    expect(await pending()).toBe('CANCELLED');
    expect(
      await db().auditLog.count({
        where: { action: 'registration.cancelledUnselected', actorId: null },
      }),
    ).toBeGreaterThanOrEqual(1);
  });

  it('BR-14: a dish replaced after closeAt shows a red note to the employees whose meal moved', async () => {
    await at(TUE, '10:30');
    const menu = await hc.get(`/api/admin/menu/weeks?canteenId=ct_gv&weekStart=${N}`).expect(200);
    const thursday = menu.body.days.find((d: { date: string }) => d.date === THU);
    const saved = await hc
      .put('/api/admin/menu/weeks')
      .send({
        canteenId: 'ct_gv',
        weekStart: N,
        reason: 'NCC hết cá',
        days: [
          {
            date: THU,
            isServing: true,
            items: thursday.items.map((i: { id: string; dishId: string }) => ({
              id: i.id,
              dishId: i.dishId === dishId.ca ? gaId : i.dishId,
            })),
            dessertIds: [dessertId],
          },
        ],
      })
      .expect(200);
    expect(saved.body.effects.movedRegistrations).toBeGreaterThanOrEqual(1);

    const ga = (await dayOf(a, THU)).items.find((i) => i.displayName === '[Mặn] Gà kho gừng')!;
    expect(ga.quantity).toBe(1);
    expect(ga.replacedAfterClose).toEqual({ from: '[Mặn] Cá basa kho' });
    expect((await dayOf(a, THU)).items.filter((i) => i.replacedAfterClose)).toHaveLength(1);
    // Someone who picks the new dish themselves gets no note.
    await b
      .put(`/api/registrations/me/days/${THU}`)
      .send({ lines: [{ menuItemId: ga.menuItemId, quantity: 1 }] })
      .expect(200);
    const mine = (await dayOf(b, THU)).items.find((i) => i.menuItemId === ga.menuItemId)!;
    expect(mine).toMatchObject({ quantity: 1, replacedAfterClose: null });
    // Dismiss the in-app notice, so other test files counting banners are not affected.
    const banners = (await a.get('/api/me/banners').expect(200)).body.items as Array<{
      notificationId?: string;
      notificationType?: string;
      params: Record<string, string>;
    }>;
    const notice = banners.find(
      (n) => n.notificationType === 'MENU_DISH_REPLACED' && n.params.date === THU,
    )!;
    expect(notice.params).toMatchObject({ from: '[Mặn] Cá basa kho', to: '[Mặn] Gà kho gừng' });
    await a.post(`/api/me/notifications/${notice.notificationId}/read`).expect(200);
  });

  it('BR-36: a leaver loses their meals from the end date on sync', async () => {
    await at(WED, '09:00');
    const end = THU;
    const leaving = C;
    class LeaverDmp extends MockDmpClient {
      override async getEmployees() {
        return (await super.getEmployees()).map((e) =>
          e.employeeCode === leaving ? { ...e, endDate: end } : e,
        );
      }
    }
    const sync = new OrgSyncService(
      app.get(PrismaService),
      app.get(ClockService),
      app.get(AuditService),
      app.get(SystemAdminBootstrap),
      app.get(SettingsService),
      new LeaverDmp('on-time'),
    );
    const result = await sync.run('fac_gv', null);
    expect(result.status).toBe('SUCCESS');
    expect(result.details!.mealsCancelledForLeavers).toBe(2);
    const regs = await db().registration.findMany({
      where: { employee: { employeeCode: C }, date: { gte: new Date(`${N}T00:00:00Z`) } },
      orderBy: { date: 'asc' },
    });
    expect(regs.map((r) => [r.date.toISOString().slice(0, 10), r.status])).toEqual([
      [WED, 'ACTIVE'],
      [THU, 'CANCELLED'],
      [FRI, 'CANCELLED'],
    ]);
    // The leaver can no longer register for those days.
    const signedIn = await signInReady(app, C);
    await signedIn
      .put(`/api/registrations/me/days/${FRI}`)
      .send({ lines: [line(N, FRI, 'ca', 1)] })
      .expect(409);
  });

  it('/history shows past days with forecast, actual and status', async () => {
    await at(WED, '12:00');
    const res = await a.get(`/api/registrations/me/history?from=${MON}&to=${WED}`).expect(200);
    const monday = res.body.items.find((d: { date: string }) => d.date === MON);
    expect(monday).toMatchObject({ forecast: 1, actual: 3, status: 'REGISTERED' });
    await a.get(`/api/registrations/me/history?from=${WED}&to=${MON}`).expect(400);
  });

  it('BR-07: the forecast snapshot is unchanged after every edit, extra, removal and cancellation', async () => {
    expect(await forecastSnapshot(N)).toEqual(forecast);
    await expect(
      db().forecastLine.deleteMany({ where: { id: forecast[0]!.id } }),
    ).rejects.toThrow();
  });
});

describe('BR-07: a missed close-week job heals itself on the next request', () => {
  it('without the job, the first request after closeAt takes the snapshot before any change', async () => {
    await at(WED, '13:00');
    await a
      .put(`/api/registrations/me/days/${N1}`)
      .send({ lines: [line(N1, N1, 'ca', 1)] })
      .expect(200);
    // closeAt of week N+1 is Friday of week N, 00:00. No job runs.
    await at(FRI, '00:01');
    const res = await a
      .put(`/api/registrations/me/days/${N1}`)
      .send({ lines: [line(N1, N1, 'ca', 2)] })
      .expect(200);
    expect(res.body.view.day).toMatchObject({ forecastTotal: 1, actualTotal: 2, changed: true });
    const lines = await forecastSnapshot(N1);
    expect(lines.map((l) => l.quantity)).toEqual([1]);
  });
});

describe('Khối / Trung tâm / Phòng ban filter on the not-registered list', () => {
  it('lists only nodes in scope, and narrows the list to a node', async () => {
    const options = (await dept.get('/api/admin/registrations/org-units').expect(200)).body.items;
    expect(options.every((o: { id: string }) => o.id !== 'ou_XKH')).toBe(true);
    expect(options[0]).toMatchObject({ id: 'ou_XCT', levelName: 'Phòng/Xưởng', level: 3 });
    const all = await hc.get(`/api/admin/registrations/not-registered?weekStart=${N2}`).expect(200);
    const team = await hc
      .get(`/api/admin/registrations/not-registered?weekStart=${N2}&orgUnitId=${UNIT}`)
      .expect(200);
    expect(team.body.total).toBeGreaterThan(0);
    expect(team.body.total).toBeLessThan(all.body.total);
    // A dept admin filtering on a node outside their scope gets nobody, never more.
    const outside = await dept
      .get(`/api/admin/registrations/not-registered?weekStart=${N2}&orgUnitId=ou_XKH`)
      .expect(200);
    expect(outside.body.total).toBe(0);
  });
});

describe('Excel import of a unit’s week (BR-21 rules)', () => {
  const base = `/api/admin/registrations/units/${UNIT}`;
  async function template() {
    const res = await dept
      .get(`${base}/import-template?weekStart=${N2}`)
      .buffer(true)
      .parse((r, cb) => {
        const chunks: Buffer[] = [];
        r.on('data', (c: Buffer) => chunks.push(c));
        r.on('end', () => cb(null, Buffer.concat(chunks)));
      })
      .expect(200);
    const wb = new ExcelJS.Workbook();
    await wb.xlsx.load(res.body as ArrayBuffer);
    return wb;
  }
  const upload = async (wb: ExcelJS.Workbook, apply: boolean) =>
    (
      await dept
        .post(`${base}/import?weekStart=${N2}&apply=${apply}`)
        .attach('file', Buffer.from(await wb.xlsx.writeBuffer()), 'dang-ky.xlsx')
        .expect(200)
    ).body as RegistrationImportView;

  it('downloads the grid as a file, previews the changes and applies them at once', async () => {
    const wb = await template();
    const sheet = wb.worksheets[0]!;
    expect(sheet.getRow(1).getCell(1).text).toBe('Mã NV');
    expect(sheet.getRow(2).hidden).toBe(true);
    const row = sheet.getRow(3);
    const code = row.getCell(1).text;
    // Unchanged file: nothing to do.
    expect(await upload(wb, false)).toMatchObject({ status: 'PREVIEW', changedDays: 0 });
    // 2 meals on the first column (Monday's first dish) and 1 on the last.
    row.getCell(4).value = 2;
    row.getCell(sheet.getRow(2).cellCount).value = 1;
    const preview = await upload(wb, false);
    expect(preview).toMatchObject({ status: 'PREVIEW', changedDays: 2, changedEmployees: 1 });
    const applied = await upload(wb, true);
    expect(applied).toMatchObject({ status: 'APPLIED', changedDays: 2 });
    const grid = (await dept.get(`${base}/grid?weekStart=${N2}`).expect(200))
      .body as RegistrationGridView;
    const mine = grid.rows.find((r) => r.employeeCode === code)!;
    expect(mine.cells[N2]!.quantities[grid.days[0]!.items[0]!.id]).toBe(2);
    expect(
      await db().auditLog.count({
        where: { action: 'registration.onBehalf', entityId: { startsWith: `${code}:` } },
      }),
    ).toBeGreaterThanOrEqual(2);
  });

  it('one bad cell rejects the whole file and saves nothing', async () => {
    const wb = await template();
    const sheet = wb.worksheets[0]!;
    sheet.getRow(3).getCell(4).value = 5;
    sheet.getRow(4).getCell(4).value = '1.5';
    sheet.addRow(['PNJP999999']);
    const res = await upload(wb, true);
    expect(res.status).toBe('REJECTED');
    expect(res.errors.map((e) => e.code)).toEqual(['QUANTITY_INVALID', 'EMPLOYEE_NOT_IN_LIST']);
    const grid = (await dept.get(`${base}/grid?weekStart=${N2}`).expect(200))
      .body as RegistrationGridView;
    const first = grid.rows.find((r) => r.employeeCode === sheet.getRow(3).getCell(1).text)!;
    expect(first.cells[N2]!.quantities[grid.days[0]!.items[0]!.id]).not.toBe(5);
  });

  it('a file that is not the template is refused', async () => {
    await dept
      .post(`${base}/import?weekStart=${N2}`)
      .attach('file', Buffer.from('not excel'), 'x.xlsx')
      .expect(400);
  });
});

describe('plan 7.1 rule 5: a 403 for every admin endpoint out of scope', () => {
  const grid: Endpoint = {
    method: 'get',
    url: `/api/admin/registrations/units/${UNIT}/grid?weekStart=${N2}`,
  };
  const save: Endpoint = {
    method: 'put',
    url: `/api/admin/registrations/units/${UNIT}/days/${addDays(N2, 4)}`,
    body: { canteenId: 'ct_gv', rows: [{ employeeCode: DEMO.employee, lines: [] }] },
  };
  const extra: Endpoint = {
    method: 'post',
    url: `/api/admin/registrations/employees/${DEMO.employee}/extras`,
    body: { date: FRI, menuItemId: 'x', quantity: 1, reason: 'x' },
  };
  const template: Endpoint = {
    method: 'get',
    url: `/api/admin/registrations/units/${UNIT}/import-template?weekStart=${N2}`,
  };
  const importWeek: Endpoint = {
    method: 'post',
    url: `/api/admin/registrations/units/${UNIT}/import?weekStart=${N2}`,
  };
  const lists: Endpoint[] = [
    { method: 'get', url: '/api/admin/registrations/units' },
    { method: 'get', url: '/api/admin/registrations/org-units' },
    { method: 'get', url: `/api/admin/registrations/progress?weekStart=${N2}` },
    { method: 'get', url: `/api/admin/registrations/not-registered?weekStart=${N2}` },
  ];

  it('a dept admin of another workshop cannot open, save or add extras for these units', async () => {
    await expectForbidden([deptOther], [grid, save, extra, template, importWeek]);
  });

  it('employees and the system admin cannot use any registration admin endpoint', async () => {
    await expectForbidden([a, sys], [...lists, grid, save, extra, template, importWeek]);
  });

  it('BR-27: an employee cannot view another unit’s colleagues; no meal unit, no team', async () => {
    await expectForbidden([a], [{ method: 'get', url: '/api/team/units/ou_XKH-DUC' }]);
    await expectForbidden([unassigned], [{ method: 'get', url: '/api/team' }]);
    await a.get('/api/team/units/ou_XCT-A1').expect(200);
  });
});
