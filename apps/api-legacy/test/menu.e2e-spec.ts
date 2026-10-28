import type { INestApplication } from '@nestjs/common';
import type { MenuWeekView } from '@meal/shared';
import ExcelJS from 'exceljs';
import request from 'supertest';
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

/**
 * P1 exit criteria: the HC admin can build and publish a week; employees see their canteen's
 * published menu and never a draft. Plus BR-08, BR-10 to BR-15 and the 403s of every endpoint.
 *
 * Week N starts Monday 2027-03-01; week N-1 is 2027-02-22..28, so the regular closeAt is
 * 00:00 Friday 2027-02-26 Vietnam time (2027-02-25T17:00Z).
 */
const WEEK = '2027-03-01';
const CANTEEN = 'ct_gv';
const TYPE = { MAN: 'dt_fac_gv_MAN', CHAY: 'dt_fac_gv_CHAY', CHAO: 'dt_fac_gv_CHAO' } as const;
const COLLEAGUE = 'PNJP000060'; // plain employee under the production meal units

let app: INestApplication;
let hc: Agent;
let employee: Agent;
let colleague: Agent;
let unassigned: Agent;
let deptProd: Agent;
let sys: Agent;

const dish: Record<string, string> = {};
const dessert: Record<string, string> = {};
let week: MenuWeekView;

async function signInAll() {
  [hc, employee, colleague, unassigned, deptProd, sys] = await Promise.all([
    signInReady(app, DEMO.hcAdmin),
    signInReady(app, DEMO.employee),
    signInReady(app, COLLEAGUE),
    signInReady(app, DEMO.unassigned),
    signInReady(app, DEMO.deptAdminProduction),
    signInReady(app, DEMO.sysAdmin),
  ]);
}

const day = (date: string, dishKeys: string[], dessertKeys: string[] = []) => ({
  date,
  isServing: true,
  items: dishKeys.map((k) => ({ dishId: dish[k]! })),
  dessertIds: dessertKeys.map((k) => dessert[k]!),
});
const off = (date: string) => ({ date, isServing: false, items: [], dessertIds: [] });
const STANDARD = ['chay', 'chao', 'ca', 'thit'];
const dishBody = (name: string, type: keyof typeof TYPE, canteenId: string = CANTEEN) => ({
  canteenId,
  name,
  typeId: TYPE[type],
  sessions: ['LUNCH'],
});

beforeAll(async () => {
  app = await createTestApp();
  setVnTime(app, '2027-02-22', '10:00'); // Monday of week N-1
  await signInAll();
});

afterAll(async () => {
  // Leave nothing behind that other files count (the seed spec counts canteens).
  const testCanteen = await db().canteen.findFirst({ where: { code: 'CT-TEST' } });
  if (testCanteen) {
    await db().orgUnit.update({ where: { id: 'ou_IT' }, data: { canteenId: null } });
    const weeks = await db().menuWeek.findMany({ where: { canteenId: testCanteen.id } });
    const days = await db().menuDay.findMany({
      where: { menuWeekId: { in: weeks.map((w) => w.id) } },
    });
    const dayIds = days.map((d) => d.id);
    await db().menuDayDessert.deleteMany({ where: { menuDayId: { in: dayIds } } });
    await db().menuItem.updateMany({
      where: { menuDayId: { in: dayIds } },
      data: { replacedByItemId: null },
    });
    await db().menuItem.deleteMany({ where: { menuDayId: { in: dayIds } } });
    await db().menuDay.deleteMany({ where: { id: { in: dayIds } } });
    await db().menuWeek.deleteMany({ where: { canteenId: testCanteen.id } });
    await db().dish.deleteMany({ where: { canteenId: testCanteen.id } });
    await db().dessert.deleteMany({ where: { canteenId: testCanteen.id } });
    await db().canteen.delete({ where: { id: testCanteen.id } });
  }
  await app.close();
  await closeDb();
});

describe('BR-10: dish and dessert libraries', () => {
  it('BR-10: the HC admin adds main dishes with a type and sessions, shown as "[Type] Name"', async () => {
    const defs: Array<[string, string, keyof typeof TYPE]> = [
      ['chay', 'Đậu hũ sốt cà', 'CHAY'],
      ['chao', 'Cháo gà', 'CHAO'],
      ['ca', 'Cá kho tộ', 'MAN'],
      ['thit', 'Thịt kho trứng', 'MAN'],
      ['ga', 'Gà chiên mắm', 'MAN'],
      ['suon', 'Sườn ram', 'MAN'],
      ['old', 'Món cũ', 'MAN'],
    ];
    for (const [key, name, type] of defs) {
      const res = await hc.post('/api/admin/dishes').send(dishBody(name, type)).expect(201);
      dish[key] = res.body.id;
    }
    const list = await hc.get(`/api/admin/dishes?canteenId=${CANTEEN}`).expect(200);
    const ca = list.body.items.find((d: { id: string }) => d.id === dish.ca);
    expect(ca.displayName).toBe('[Mặn] Cá kho tộ');
    expect(ca.type).toEqual({ id: TYPE.MAN, name: 'Mặn', color: '#b91c1c' });
    for (const [key, name] of [
      ['chuoi', 'Chuối'],
      ['raucau', 'Rau câu'],
      ['sua', 'Sữa chua'],
    ] as const) {
      dessert[key] = (
        await hc.post('/api/admin/desserts').send({ canteenId: CANTEEN, name }).expect(201)
      ).body.id;
    }
  });

  it('BR-10: the same dish cannot be added twice, whatever its case or spacing', async () => {
    const res = await hc
      .post('/api/admin/dishes')
      .send(dishBody('  cá KHO   tộ ', 'MAN'))
      .expect(409);
    expect(res.body).toMatchObject({ code: 'CONFLICT', details: [{ message: 'DUPLICATE' }] });
    // Two identical requests at once: only one is stored.
    const twice = await Promise.all([
      hc.post('/api/admin/dishes').send(dishBody('Canh chua', 'MAN')),
      hc.post('/api/admin/dishes').send(dishBody('Canh chua', 'MAN')),
    ]);
    expect(twice.map((r) => r.status).sort()).toEqual([201, 409]);
    expect(await db().dish.count({ where: { name: 'Canh chua' } })).toBe(1);
    await hc.post('/api/admin/desserts').send({ canteenId: CANTEEN, name: 'chuối' }).expect(409);
    // Another type is another dish; a name is stored trimmed with single spaces.
    const chay = await hc
      .post('/api/admin/dishes')
      .send(dishBody(' Cá   kho tộ', 'CHAY'))
      .expect(201);
    expect(chay.body.name).toBe('Cá kho tộ');
    await hc.post(`/api/admin/dishes/${chay.body.id}/archive`).expect(200);
    // An archived copy still counts: restore it instead of adding it again.
    const again = await hc
      .post('/api/admin/dishes')
      .send(dishBody('cá kho tộ', 'CHAY'))
      .expect(409);
    expect(again.body.details[0]).toMatchObject({
      message: 'DUPLICATE_ARCHIVED',
      id: chay.body.id,
    });
  });

  it('BR-10: each canteen has its own library', async () => {
    await hc.get('/api/admin/dishes').expect(400); // canteenId is required
    await hc.get('/api/admin/dishes?canteenId=nope').expect(404);
    const other = await db().canteen.create({
      data: { factoryId: 'fac_gv', code: 'CT-TEST', name: 'Nhà ăn thử', supplierId: 'sup_gv' },
    });
    // The same name is fine in another canteen's library.
    const res = await hc
      .post('/api/admin/dishes')
      .send(dishBody('Cá kho tộ', 'MAN', other.id))
      .expect(201);
    dish.otherCa = res.body.id;
    dessert.otherChuoi = (
      await hc.post('/api/admin/desserts').send({ canteenId: other.id, name: 'Chuối' }).expect(201)
    ).body.id;
    const list = await hc.get(`/api/admin/dishes?canteenId=${other.id}`).expect(200);
    expect(list.body.items.map((d: { id: string }) => d.id)).toEqual([dish.otherCa]);
    // A dish of another canteen cannot go on this canteen's menu.
    await hc
      .put('/api/admin/menu/weeks')
      .send({ canteenId: CANTEEN, weekStart: WEEK, days: [day('2027-03-01', ['otherCa'])] })
      .expect(400);
  });

  it('BR-10: name, type and at least one session are required', async () => {
    await hc.post('/api/admin/dishes').send(dishBody('', 'MAN')).expect(400);
    await hc
      .post('/api/admin/dishes')
      .send({ ...dishBody('X', 'MAN'), typeId: 'dt_nope' })
      .expect(400);
    await hc
      .post('/api/admin/dishes')
      .send({ ...dishBody('X', 'MAN'), sessions: [] })
      .expect(400);
    await hc.post('/api/admin/desserts').send({ canteenId: CANTEEN, name: ' ' }).expect(400);
  });

  it('BR-10: edits are audited; archived dishes leave the default list but are never deleted', async () => {
    await hc
      .put(`/api/admin/dishes/${dish.old}`)
      .send({ name: 'Món cũ (bỏ)', typeId: TYPE.MAN, sessions: ['LUNCH'] })
      .expect(200);
    await hc.post(`/api/admin/dishes/${dish.old}/archive`).expect(200);
    const active = await hc.get(`/api/admin/dishes?canteenId=${CANTEEN}`).expect(200);
    expect(active.body.items.some((d: { id: string }) => d.id === dish.old)).toBe(false);
    const all = await hc
      .get(`/api/admin/dishes?canteenId=${CANTEEN}&includeArchived=true`)
      .expect(200);
    expect(all.body.items.find((d: { id: string }) => d.id === dish.old).archivedAt).not.toBeNull();
    expect(await db().dish.count({ where: { id: dish.old } })).toBe(1);
    const audits = await db().auditLog.findMany({ where: { entityId: dish.old } });
    expect(audits.map((a) => a.action)).toEqual(['dish.created', 'dish.updated', 'dish.archived']);
  });
});

describe('BR-10 / BR-13: dish types are master data', () => {
  it('BR-10: the HC admin adds a type with a colour; it is shown with dishes and has no bounds by default', async () => {
    const created = await hc
      .post('/api/admin/dish-types')
      .send({ code: 'lowcarb', name: 'Low-carb', color: '#7C3AED', sortOrder: 9 })
      .expect(201);
    const list = await hc.get('/api/admin/dish-types').expect(200);
    const lowcarb = list.body.items.find((t: { id: string }) => t.id === created.body.id);
    expect(lowcarb).toMatchObject({
      code: 'LOWCARB',
      color: '#7c3aed',
      minPerDay: null,
      maxPerDay: null,
      active: true,
    });
    expect(list.body.items.map((t: { code: string }) => t.code).slice(0, 3)).toEqual([
      'MAN',
      'CHAY',
      'CHAO',
    ]);
    await hc
      .post('/api/admin/dish-types')
      .send({ code: 'LOWCARB', name: 'X', color: '#000000' })
      .expect(409);
    await hc
      .post('/api/admin/dish-types')
      .send({ code: 'X1', name: 'X', color: 'red' })
      .expect(400);
    await hc
      .patch(`/api/admin/dish-types/${created.body.id}`)
      .send({ minPerDay: 2, maxPerDay: 1 })
      .expect(400);
    await hc
      .patch(`/api/admin/dish-types/${created.body.id}`)
      .send({ name: 'Eat clean / Low-carb', active: false })
      .expect(200);
    // An inactive type takes no new dishes.
    await hc
      .post('/api/admin/dishes')
      .send({ canteenId: CANTEEN, name: 'Ức gà', typeId: created.body.id, sessions: ['LUNCH'] })
      .expect(400);
    const audits = await db().auditLog.findMany({ where: { entityId: created.body.id } });
    expect(audits.map((a) => a.action)).toEqual(['dishType.created', 'dishType.updated']);
  });
});

describe('building and publishing a week (BR-08, BR-11, BR-13, BR-03)', () => {
  it('BR-08: a new week offers Monday to Sunday, serving Monday to Friday, with the expected closeAt', async () => {
    const res = await hc
      .get(`/api/admin/menu/weeks?canteenId=${CANTEEN}&weekStart=${WEEK}`)
      .expect(200);
    expect(res.body.status).toBe('NEW');
    expect(res.body.days.map((d: { isServing: boolean }) => d.isServing)).toEqual([
      true,
      true,
      true,
      true,
      true,
      false,
      false,
    ]);
    expect(res.body.days.at(-1).date).toBe('2027-03-07'); // Sunday can be turned on too
    expect(res.body.closeAtIsExpected).toBe(true);
    expect(res.body.closeAt).toBe('2027-02-25T17:00:00.000Z');
  });

  it('BR-10: an archived dish cannot be put on a menu', async () => {
    await hc
      .put('/api/admin/menu/weeks')
      .send({ canteenId: CANTEEN, weekStart: WEEK, days: [day('2027-03-01', ['old'])] })
      .expect(400);
  });

  it('BR-11 / BR-08 / BR-13: saves a draft with desserts, Saturday on, and warns about an unusual mix', async () => {
    const res = await hc
      .put('/api/admin/menu/weeks')
      .send({
        canteenId: CANTEEN,
        weekStart: WEEK,
        days: [
          day('2027-03-01', STANDARD, ['chuoi']),
          day('2027-03-02', [...STANDARD, 'ga'], ['raucau']),
          day('2027-03-03', ['ca']),
          day('2027-03-04', STANDARD),
          day('2027-03-05', STANDARD, ['sua']),
          day('2027-03-06', ['chay', 'ca', 'thit'], ['chuoi']),
        ],
      })
      .expect(200);
    week = res.body.menu;
    expect(week.status).toBe('DRAFT');
    expect(week.days.map((d) => d.mix?.usual ?? null)).toEqual([
      true,
      true,
      false,
      true,
      true,
      false,
      null,
    ]);
    expect(week.days[2]!.mix!.issues.map((i) => i.typeName)).toEqual(['Mặn', 'Chay', 'Cháo']);
    expect(week.days[5]!.isServing).toBe(true); // Saturday turned on for this week
    expect(week.days[0]!.desserts).toEqual([{ id: dessert.chuoi, name: 'Chuối' }]);
    expect(week.days[0]!.items.map((i) => i.displayName)).toEqual([
      '[Chay] Đậu hũ sốt cà',
      '[Cháo] Cháo gà',
      '[Mặn] Cá kho tộ',
      '[Mặn] Thịt kho trứng',
    ]);
  });

  it('BR-11: the same dish twice on a day and a non-serving day with dishes are rejected', async () => {
    await hc
      .put('/api/admin/menu/weeks')
      .send({ canteenId: CANTEEN, weekStart: WEEK, days: [day('2027-03-04', ['ca', 'ca'])] })
      .expect(400);
    await hc
      .put('/api/admin/menu/weeks')
      .send({
        canteenId: CANTEEN,
        weekStart: WEEK,
        days: [{ ...day('2027-03-04', ['ca']), isServing: false }],
      })
      .expect(400);
    await hc
      .put('/api/admin/menu/weeks')
      .send({ canteenId: CANTEEN, weekStart: '2027-03-02', days: [off('2027-03-02')] })
      .expect(400); // not a Monday
  });

  it('BR-02: employees never see a draft', async () => {
    const res = await employee.get(`/api/menu/my-week?weekStart=${WEEK}`).expect(200);
    expect(res.body).toEqual({
      state: 'NOT_PUBLISHED',
      weekStart: WEEK,
      canteenName: 'Nhà ăn Gò Vấp',
    });
  });

  it('a week with a serving day lacking a main dish or a dessert cannot be published', async () => {
    const res = await hc.post(`/api/admin/menu/weeks/${week.id}/publish`).expect(409);
    expect(res.body.code).toBe('MENU_INCOMPLETE');
    expect(res.body.details).toEqual([
      { date: '2027-03-03', gaps: ['DESSERT'] },
      { date: '2027-03-04', gaps: ['DESSERT'] },
    ]);
    const direct = await hc
      .post('/api/admin/menu/weeks/publish')
      .send({ canteenId: CANTEEN, weekStart: WEEK, days: [day('2027-03-03', [], ['chuoi'])] })
      .expect(409);
    expect(direct.body.details).toEqual([{ date: '2027-03-03', gaps: ['MAIN'] }]);
    expect((await db().menuWeek.findUniqueOrThrow({ where: { id: week.id! } })).status).toBe(
      'DRAFT',
    );
  });

  it('BR-03: publishing on Monday of week N-1 fixes closeAt at the regular 00:00 Friday', async () => {
    // Publishing straight from the builder saves the submitted days first (no draft step).
    const res = await hc
      .post('/api/admin/menu/weeks/publish')
      .send({
        canteenId: CANTEEN,
        weekStart: WEEK,
        days: [day('2027-03-03', ['ca'], ['raucau']), day('2027-03-04', STANDARD, ['chuoi'])],
      })
      .expect(200);
    expect(res.body.status).toBe('PUBLISHED');
    expect(res.body.closeAt).toBe('2027-02-25T17:00:00.000Z');
    expect(res.body.closeAtIsExpected).toBe(false);
    expect(res.body.latePublish).toBe(false);
    const audit = await db().auditLog.findFirstOrThrow({
      where: { action: 'menu.published', entityId: week.id! },
    });
    expect(audit.after).toMatchObject({ closeAt: '2027-02-25T17:00:00.000Z', latePublish: false });
  });

  it('publishing twice is refused', async () => {
    const res = await hc.post(`/api/admin/menu/weeks/${week.id}/publish`).expect(409);
    expect(res.body.code).toBe('INVALID_STATE');
  });
});

describe("employees see their canteen's published menu (BR-02, BR-15, BR-63)", () => {
  it('BR-02: after publishing the employee sees the serving days, meals and desserts', async () => {
    const res = await employee.get(`/api/menu/my-week?weekStart=${WEEK}`).expect(200);
    expect(res.body.state).toBe('PUBLISHED');
    const menu: MenuWeekView = res.body.menu;
    expect(menu.canteenName).toBe('Nhà ăn Gò Vấp');
    expect(menu.closeAt).toBe('2027-02-25T17:00:00.000Z');
    expect(menu.days.map((d) => d.date)).toEqual([
      '2027-03-01',
      '2027-03-02',
      '2027-03-03',
      '2027-03-04',
      '2027-03-05',
      '2027-03-06',
    ]);
    expect(menu.days[2]!.desserts).toEqual([{ id: dessert.raucau, name: 'Rau câu' }]);
    expect(menu.days[0]!.items.map((i) => i.displayName)).toContain('[Mặn] Cá kho tộ');
    expect(menu.days[0]!.mix).toBeNull(); // the mix warning is for the HC admin only
  });

  it('defaults to the coming week', async () => {
    const res = await employee.get('/api/menu/my-week').expect(200);
    expect(res.body.menu.weekStart).toBe(WEEK);
  });

  it('BR-60 / BR-63: an employee on the "unassigned" list sees no menu', async () => {
    const res = await unassigned.get(`/api/menu/my-week?weekStart=${WEEK}`).expect(200);
    expect(res.body).toEqual({ state: 'UNASSIGNED', weekStart: WEEK });
  });

  it("BR-15: menus are per canteen; an employee sees only their meal unit's canteen", async () => {
    // A second canteen (created with its library above), mapped onto Phòng IT (where the
    // system admin works).
    const other = await db().canteen.findFirstOrThrow({ where: { code: 'CT-TEST' } });
    const otherWeek = '2027-03-15';
    const saved = await hc
      .put('/api/admin/menu/weeks')
      .send({
        canteenId: other.id,
        weekStart: otherWeek,
        days: [day('2027-03-15', ['otherCa'], ['otherChuoi'])],
      })
      .expect(200);
    await hc.post(`/api/admin/menu/weeks/${saved.body.menu.id}/publish`).expect(200);
    await db().orgUnit.update({ where: { id: 'ou_IT' }, data: { canteenId: other.id } });

    const itStaff = await sys.get(`/api/menu/my-week?weekStart=${otherWeek}`).expect(200);
    expect(itStaff.body.state).toBe('PUBLISHED');
    expect(itStaff.body.menu.canteenName).toBe('Nhà ăn thử');
    const production = await employee.get(`/api/menu/my-week?weekStart=${otherWeek}`).expect(200);
    expect(production.body).toMatchObject({ state: 'NOT_PUBLISHED', canteenName: 'Nhà ăn Gò Vấp' });
  });
});

describe('BR-14: editing a published menu', () => {
  let monday: MenuWeekView['days'][number];

  beforeAll(async () => {
    const res = await hc.get(`/api/admin/menu/weeks?canteenId=${CANTEEN}&weekStart=${WEEK}`);
    monday = (res.body as MenuWeekView).days[0]!;
    const item = (key: string) => monday.items.find((i) => i.dishId === dish[key])!.id;
    const [a, b] = await Promise.all([
      db().employee.findUniqueOrThrow({ where: { employeeCode: DEMO.employee } }),
      db().employee.findUniqueOrThrow({ where: { employeeCode: COLLEAGUE } }),
    ]);
    const date = new Date('2027-03-01T00:00:00.000Z');
    await db().registration.createMany({
      data: [
        { employeeId: a.id, menuItemId: item('ca'), date, quantity: 2, source: 'SELF' },
        { employeeId: a.id, menuItemId: item('thit'), date, quantity: 1, source: 'SELF' },
        { employeeId: b.id, menuItemId: item('ca'), date, quantity: 1, source: 'SELF' },
      ],
    });
  });

  it('BR-14: replacing a dish moves its registrations; removing one flags them; both notify', async () => {
    const ca = monday.items.find((i) => i.dishId === dish.ca)!;
    const thit = monday.items.find((i) => i.dishId === dish.thit)!;
    const items = monday.items
      .filter((i) => i.id !== thit.id)
      .map((i) =>
        i.id === ca.id ? { id: i.id, dishId: dish.suon } : { id: i.id, dishId: i.dishId },
      );
    const res = await hc
      .put('/api/admin/menu/weeks')
      .send({
        canteenId: CANTEEN,
        weekStart: WEEK,
        reason: 'NCC đổi món',
        days: [{ date: '2027-03-01', isServing: true, items, dessertIds: [dessert.chuoi] }],
      })
      .expect(200);
    expect(res.body.effects).toEqual({
      movedRegistrations: 2,
      flaggedRegistrations: 1,
      notifiedEmployees: 2,
    });

    const oldItem = await db().menuItem.findUniqueOrThrow({ where: { id: ca.id } });
    expect(oldItem.removedAt).not.toBeNull();
    expect(oldItem.replacedByItemId).not.toBeNull();
    const moved = await db().registration.findMany({
      where: { menuItemId: oldItem.replacedByItemId! },
    });
    expect(moved.map((r) => r.quantity).sort()).toEqual([1, 2]);
    expect(moved.every((r) => r.status === 'ACTIVE')).toBe(true);
    const flagged = await db().registration.findMany({ where: { menuItemId: thit.id } });
    expect(flagged.map((r) => r.status)).toEqual(['NEEDS_RESELECT']);

    const audit = await db().auditLog.findFirstOrThrow({
      where: { action: 'menu.edited', entityId: week.id! },
    });
    expect(audit.reason).toBe('NCC đổi món');
  });

  it('BR-14: the employee sees in-app notices and can dismiss only their own', async () => {
    const res = await employee.get('/api/me/banners').expect(200);
    const notices = res.body.items.filter((b: { kind: string }) => b.kind === 'NOTIFICATION');
    expect(notices.map((n: { notificationType: string }) => n.notificationType).sort()).toEqual([
      'MENU_DISH_REMOVED',
      'MENU_DISH_REPLACED',
    ]);
    const replaced = notices.find(
      (n: { notificationType: string }) => n.notificationType === 'MENU_DISH_REPLACED',
    );
    expect(replaced.params).toMatchObject({
      date: '2027-03-01',
      from: '[Mặn] Cá kho tộ',
      to: '[Mặn] Sườn ram',
    });
    const removed = notices.find(
      (n: { notificationType: string }) => n.notificationType === 'MENU_DISH_REMOVED',
    );
    expect(removed.params).toMatchObject({ lockTime: '08:00' });

    // A colleague cannot dismiss someone else's notice.
    await colleague.post(`/api/me/notifications/${replaced.notificationId}/read`).expect(404);
    await employee.post(`/api/me/notifications/${replaced.notificationId}/read`).expect(200);
    const after = await employee.get('/api/me/banners').expect(200);
    expect(after.body.items).toHaveLength(notices.length - 1);
  });

  it('BR-14: once a day has locked (08:00) it can no longer change; later days still can', async () => {
    setVnTime(app, '2027-03-01', '08:00');
    await signInAll();
    const current = (
      await hc.get(`/api/admin/menu/weeks?canteenId=${CANTEEN}&weekStart=${WEEK}`).expect(200)
    ).body as MenuWeekView;
    expect(current.lockedDates).toEqual(['2027-03-01']);
    const keep = (d: MenuWeekView['days'][number]) => ({
      date: d.date,
      isServing: d.isServing,
      items: d.items.map((i) => ({ id: i.id, dishId: i.dishId })),
      dessertIds: d.desserts.map((x) => x.id),
    });
    const monday = keep(current.days[0]!);
    const res = await hc
      .put('/api/admin/menu/weeks')
      .send({
        canteenId: CANTEEN,
        weekStart: WEEK,
        days: [{ ...monday, items: [...monday.items, { dishId: dish.ga }] }],
      })
      .expect(409);
    expect(res.body.code).toBe('INVALID_STATE');

    const wednesday = keep(current.days[2]!);
    await hc
      .put('/api/admin/menu/weeks')
      .send({
        canteenId: CANTEEN,
        weekStart: WEEK,
        days: [{ ...wednesday, items: [...wednesday.items, { dishId: dish.chay }] }],
      })
      .expect(200);
  });

  it('BR-08: a published serving day cannot be turned off', async () => {
    const res = await hc
      .put('/api/admin/menu/weeks')
      .send({ canteenId: CANTEEN, weekStart: WEEK, days: [off('2027-03-04')] })
      .expect(409);
    expect(res.body.code).toBe('INVALID_STATE');
  });
});

describe('BR-04: late publish', () => {
  it('BR-04: a menu published on Saturday after the regular close gets closeAt = publish + 24 h', async () => {
    setVnTime(app, '2027-03-06', '09:30'); // Saturday; week 2027-03-08 closed regularly Friday 00:00
    await signInAll();
    const saved = await hc
      .put('/api/admin/menu/weeks')
      .send({
        canteenId: CANTEEN,
        weekStart: '2027-03-08',
        days: [day('2027-03-08', STANDARD, ['chuoi'])],
      })
      .expect(200);
    expect(saved.body.menu.latePublish).toBe(true);
    const res = await hc.post(`/api/admin/menu/weeks/${saved.body.menu.id}/publish`).expect(200);
    expect(res.body.closeAt).toBe('2027-03-07T02:30:00.000Z'); // Sunday 09:30 Vietnam time
    expect(res.body.latePublish).toBe(true);
  });
});

describe('BR-10: Excel import of a canteen library', () => {
  const HEADER = ['Nhóm (Món chính / Tráng miệng)', 'Mã loại món (món chính)', 'Tên món'];
  async function file(rows: string[][], header = HEADER) {
    const wb = new ExcelJS.Workbook();
    const sheet = wb.addWorksheet('Thư viện món');
    sheet.addRow(header);
    for (const r of rows) sheet.addRow(r);
    return Buffer.from(await wb.xlsx.writeBuffer());
  }
  const upload = (buffer: Buffer, apply: boolean) =>
    hc
      .post(`/api/admin/dish-library/import?canteenId=${CANTEEN}&apply=${apply}`)
      .attach('file', buffer, 'thu-vien.xlsx');

  it('BR-10: the template lists the active dish-type codes', async () => {
    const res = await hc
      .get('/api/admin/dish-library/template')
      .buffer(true)
      .parse((r, cb) => {
        const chunks: Buffer[] = [];
        r.on('data', (c: Buffer) => chunks.push(c));
        r.on('end', () => cb(null, Buffer.concat(chunks)));
      })
      .expect(200);
    const wb = new ExcelJS.Workbook();
    await wb.xlsx.load(res.body as ArrayBuffer);
    const library = wb.worksheets[0]!;
    expect(library.getRow(1).getCell(1).text).toBe(HEADER[0]);
    const codes: string[] = [];
    wb.worksheets[1]!.eachRow((row, n) => n > 1 && codes.push(row.getCell(1).text));
    expect(codes).toEqual(expect.arrayContaining(['MAN', 'CHAY', 'CHAO']));
    for (const rowNumber of [2, 2001]) {
      const row = library.getRow(rowNumber);
      expect(row.getCell(1).dataValidation).toMatchObject({
        type: 'list',
        formulae: ['"Món chính,Tráng miệng"'],
      });
      expect(row.getCell(2).dataValidation).toMatchObject({
        type: 'list',
        formulae: [`'Loại món'!$A$2:$A$${codes.length + 1}`],
      });
    }
  });

  it('BR-10: previews, then adds only what the library does not have yet', async () => {
    const buffer = await file([
      ['Món chính', 'MAN', 'Cá kho tộ'], // already in the library
      ['Món chính', 'chay', 'Nấm kho tiêu'],
      ['Tráng miệng', '', 'Thạch dừa'],
    ]);
    const preview = (await upload(buffer, false).expect(200)).body;
    expect(preview).toMatchObject({ status: 'PREVIEW', rowCount: 3, alreadyInLibrary: 1 });
    expect(preview.toCreate.map((r: { name: string }) => r.name)).toEqual([
      'Nấm kho tiêu',
      'Thạch dừa',
    ]);
    expect(await db().dish.count({ where: { name: 'Nấm kho tiêu' } })).toBe(0);
    const applied = (await upload(buffer, true).expect(200)).body;
    expect(applied.status).toBe('APPLIED');
    expect(await db().dish.count({ where: { name: 'Nấm kho tiêu', canteenId: CANTEEN } })).toBe(1);
    expect(await db().dessert.count({ where: { name: 'Thạch dừa', canteenId: CANTEEN } })).toBe(1);
    // The same file again adds nothing: no dish is ever added twice.
    const again = (await upload(buffer, true).expect(200)).body;
    expect(again).toMatchObject({ toCreate: [], alreadyInLibrary: 3 });
    expect(await db().dish.count({ where: { name: 'Nấm kho tiêu' } })).toBe(1);
  });

  it('BR-10: one bad row rejects the file; a file that is not the template is refused', async () => {
    const res = (
      await upload(
        await file([
          ['Món chính', 'BBQ', 'Bò nướng'],
          ['Món chính', 'MAN', 'Gà rô ti'],
        ]),
        true,
      ).expect(200)
    ).body;
    expect(res.status).toBe('REJECTED');
    expect(res.errors).toEqual([
      { rowNumber: 2, field: 'typeCode', code: 'TYPE_UNKNOWN', value: 'BBQ' },
    ]);
    expect(await db().dish.count({ where: { name: 'Gà rô ti' } })).toBe(0);
    await upload(await file([['x']], ['Khác']), false).expect(400);
  });
});

describe('403 on every dish and menu admin endpoint', () => {
  const id = 'x';
  const body = dishBody('X', 'MAN');
  const endpoints: Endpoint[] = [
    { method: 'get', url: `/api/admin/dishes?canteenId=${CANTEEN}` },
    { method: 'post', url: '/api/admin/dishes', body },
    { method: 'put', url: `/api/admin/dishes/${id}`, body },
    { method: 'post', url: `/api/admin/dishes/${id}/archive` },
    { method: 'post', url: `/api/admin/dishes/${id}/restore` },
    { method: 'get', url: `/api/admin/desserts?canteenId=${CANTEEN}` },
    { method: 'post', url: '/api/admin/desserts', body: { canteenId: CANTEEN, name: 'X' } },
    { method: 'put', url: `/api/admin/desserts/${id}`, body: { name: 'X' } },
    { method: 'post', url: `/api/admin/desserts/${id}/archive` },
    { method: 'post', url: `/api/admin/desserts/${id}/restore` },
    { method: 'get', url: '/api/admin/menu/canteens' },
    { method: 'get', url: `/api/admin/menu/weeks?canteenId=${CANTEEN}&weekStart=${WEEK}` },
    {
      method: 'put',
      url: '/api/admin/menu/weeks',
      body: { canteenId: CANTEEN, weekStart: WEEK, days: [off(WEEK)] },
    },
    { method: 'post', url: `/api/admin/menu/weeks/${id}/publish` },
    {
      method: 'post',
      url: '/api/admin/menu/weeks/publish',
      body: { canteenId: CANTEEN, weekStart: WEEK, days: [off(WEEK)] },
    },
    { method: 'get', url: '/api/admin/dish-types' },
    {
      method: 'post',
      url: '/api/admin/dish-types',
      body: { code: 'X', name: 'X', color: '#000000' },
    },
    { method: 'patch', url: `/api/admin/dish-types/${id}`, body: { name: 'X' } },
    { method: 'get', url: '/api/admin/dish-library/template' },
    { method: 'post', url: `/api/admin/dish-library/import?canteenId=${CANTEEN}` },
  ];

  it('an employee, a dept admin and a system admin get 403 (dish library and menu are HC only)', async () => {
    await expectForbidden([employee, deptProd, sys], endpoints);
  });

  it('the employee menu needs a session', async () => {
    await request(app.getHttpServer()).get('/api/menu/my-week').expect(401);
  });
});
