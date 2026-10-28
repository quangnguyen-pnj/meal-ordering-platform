import { Controller, Get, type INestApplication } from '@nestjs/common';
import { ModulesContainer } from '@nestjs/core';
import { METHOD_METADATA } from '@nestjs/common/constants';
import request from 'supertest';
import { AUTHENTICATED_KEY, IS_PUBLIC_KEY, PERMISSION_KEY } from '../src/rbac/decorators';
import { DEMO, closeDb, createTestApp, db, signInReady, type Agent } from './helpers';

/** A route that forgets to declare its access rule, to prove the guard fails closed. */
@Controller('test-unannotated')
class UnannotatedController {
  @Get()
  forgotten() {
    return { secret: true };
  }
}

// A plain employee that no other test resets, so the session stays valid for the whole file.
const PLAIN_EMPLOYEE = 'PNJP000050';

let app: INestApplication;
let employee: Agent;
let deptProd: Agent; // dept admin of Xưởng Chế tác (XCT)
let deptJewelry: Agent; // dept admin of Xưởng Kim hoàn (XKH)
let hc: Agent;
let sys: Agent;

// Employees used as targets, found by org node so the test follows the seed.
let inProduction: string; // under XCT
let inJewelry: string; // under XKH
let inOffice: string; // under KVP

async function someoneUnder(nodeCode: string): Promise<string> {
  const row = await db().employee.findFirstOrThrow({
    where: {
      orgUnit: { path: { contains: `/ou_${nodeCode}/` } },
      NOT: { employeeCode: { in: Object.values(DEMO) } },
    },
    orderBy: { employeeCode: 'asc' },
  });
  return row.employeeCode;
}

beforeAll(async () => {
  app = await createTestApp({}, [UnannotatedController]);
  [employee, deptProd, deptJewelry, hc, sys] = await Promise.all([
    signInReady(app, PLAIN_EMPLOYEE),
    signInReady(app, DEMO.deptAdminProduction),
    signInReady(app, DEMO.deptAdminJewelry),
    signInReady(app, DEMO.hcAdmin),
    signInReady(app, DEMO.sysAdmin),
  ]);
  [inProduction, inJewelry, inOffice] = await Promise.all([
    someoneUnder('XCT'),
    someoneUnder('XKH'),
    someoneUnder('KVP'),
  ]);
});
afterAll(async () => {
  await app.close();
  await closeDb();
});

const server = () => app.getHttpServer();

describe('authentication', () => {
  it.each([
    ['GET', '/api/employees'],
    ['POST', `/api/employees/${DEMO.employee}/pin/reset`],
    ['GET', '/api/system/sync-runs'],
    ['GET', '/api/auth/me'],
  ])('401 without a session: %s %s', async (method, url) => {
    const res = await request(server())[method.toLowerCase() as 'get' | 'post'](url).expect(401);
    expect(res.body.code).toBe('UNAUTHENTICATED');
  });

  it('keeps health and login public', async () => {
    await request(server()).get('/api/health').expect(200);
  });
});

describe('403 on out-of-scope access: PIN reset', () => {
  it('an employee cannot reset anyone, not even a colleague', async () => {
    const res = await employee
      .post(`/api/employees/${inProduction}/pin/reset`)
      .send({})
      .expect(403);
    expect(res.body.code).toBe('FORBIDDEN');
  });

  it('a dept admin can reset inside assigned nodes, including child nodes', async () => {
    await deptProd.post(`/api/employees/${inProduction}/pin/reset`).send({}).expect(200);
    await deptProd.post(`/api/employees/${DEMO.employee}/pin/reset`).send({}).expect(200); // level-6 child
  });

  it('a dept admin gets 403 for employees outside assigned nodes', async () => {
    await deptProd.post(`/api/employees/${inJewelry}/pin/reset`).send({}).expect(403);
    await deptProd.post(`/api/employees/${inOffice}/pin/reset`).send({}).expect(403);
    await deptJewelry.post(`/api/employees/${inProduction}/pin/reset`).send({}).expect(403);
  });

  it('a dept admin gets 403, not 404, for a code that does not exist (no existence leak)', async () => {
    await deptProd.post('/api/employees/NOT-A-CODE/pin/reset').send({}).expect(403);
  });

  it('an out-of-scope reset changes nothing and writes no audit entry', async () => {
    const before = await db().employee.findUniqueOrThrow({ where: { employeeCode: inJewelry } });
    const auditsBefore = await db().auditLog.count({
      where: { action: 'employee.pin.reset', entityId: before.id },
    });
    await deptProd.post(`/api/employees/${inJewelry}/pin/reset`).send({}).expect(403);
    const after = await db().employee.findUniqueOrThrow({ where: { employeeCode: inJewelry } });
    expect(after.pinHash).toBe(before.pinHash);
    expect(
      await db().auditLog.count({ where: { action: 'employee.pin.reset', entityId: before.id } }),
    ).toBe(auditsBefore);
  });

  it('the HC admin reaches the whole factory; unknown codes are 404 for them', async () => {
    await hc.post(`/api/employees/${inJewelry}/pin/reset`).send({}).expect(200);
    await hc.post(`/api/employees/${inOffice}/pin/reset`).send({}).expect(200);
    await hc.post('/api/employees/NOT-A-CODE/pin/reset').send({}).expect(404);
  });

  it('the system admin can reset PINs anywhere', async () => {
    await sys.post(`/api/employees/${inOffice}/pin/reset`).send({}).expect(200);
  });
});

describe('list endpoints are filtered by scope', () => {
  it('a dept admin sees only employees under assigned nodes', async () => {
    const res = await deptProd.get('/api/employees?limit=100').expect(200);
    expect(res.body.items.length).toBeGreaterThan(0);
    const codes: string[] = res.body.items.map((i: { employeeCode: string }) => i.employeeCode);
    const outside = await db().employee.count({
      where: { employeeCode: { in: codes }, NOT: { orgUnit: { path: { contains: '/ou_XCT/' } } } },
    });
    expect(outside).toBe(0);
  });

  it('two dept admins get disjoint lists', async () => {
    const a = await deptProd.get('/api/employees?limit=100').expect(200);
    const b = await deptJewelry.get('/api/employees?limit=100').expect(200);
    const aCodes = new Set<string>(
      a.body.items.map((i: { employeeCode: string }) => i.employeeCode),
    );
    expect(b.body.items.some((i: { employeeCode: string }) => aCodes.has(i.employeeCode))).toBe(
      false,
    );
  });

  it('search cannot widen the scope', async () => {
    const res = await deptProd.get(`/api/employees?q=${inJewelry}`).expect(200);
    expect(res.body.items).toEqual([]);
  });

  it('HC and system admins see the whole factory', async () => {
    const res = await hc.get('/api/employees?limit=100').expect(200);
    expect(res.body.items).toHaveLength(100);
    expect(
      (await sys.get('/api/employees?q=PNJP0016').expect(200)).body.items.length,
    ).toBeGreaterThan(0);
  });

  it('an employee has no grant for the list at all', async () => {
    await employee.get('/api/employees').expect(403);
  });
});

describe('403 on role-level actions', () => {
  it('only HC and system admins read sync logs', async () => {
    await employee.get('/api/system/sync-runs').expect(403);
    await deptProd.get('/api/system/sync-runs').expect(403);
    await hc.get('/api/system/sync-runs').expect(200);
    await sys.get('/api/system/sync-runs').expect(200);
  });

  it('only the system admin may use the fake-time endpoint', async () => {
    await employee.get('/api/dev/clock').expect(403);
    await deptProd.get('/api/dev/clock').expect(403);
    await hc.get('/api/dev/clock').expect(403);
    await sys.get('/api/dev/clock').expect(200);
  });
});

describe('fail-closed guard', () => {
  it('denies a signed-in user on a route that declares no access rule', async () => {
    const res = await sys.get('/api/test-unannotated').expect(403);
    expect(res.body.code).toBe('FORBIDDEN');
  });

  it('every route of the real controllers declares its access rule', () => {
    const missing: string[] = [];
    const wrappers = [...app.get(ModulesContainer).values()].flatMap((m) => [
      ...m.controllers.values(),
    ]);
    expect(wrappers.length).toBeGreaterThan(4);
    for (const wrapper of wrappers) {
      const proto = wrapper.metatype?.prototype as Record<string, unknown> | undefined;
      if (!proto || wrapper.metatype === UnannotatedController) continue;
      for (const name of Object.getOwnPropertyNames(proto)) {
        const handler = proto[name];
        if (
          typeof handler !== 'function' ||
          Reflect.getMetadata(METHOD_METADATA, handler) === undefined
        )
          continue;
        const declared = [IS_PUBLIC_KEY, AUTHENTICATED_KEY, PERMISSION_KEY].some(
          (key) =>
            Reflect.getMetadata(key, handler) !== undefined ||
            Reflect.getMetadata(key, wrapper.metatype!) !== undefined,
        );
        if (!declared) missing.push(`${wrapper.metatype?.name}.${name}`);
      }
    }
    expect(missing).toEqual([]);
  });
});
