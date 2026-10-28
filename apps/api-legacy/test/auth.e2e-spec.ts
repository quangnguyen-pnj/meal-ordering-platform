import type { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { ClockService } from '../src/clock/clock.service';
import { SESSION_COOKIE } from '../src/auth/session.service';
import { INITIAL_PIN, DEMO, closeDb, createTestApp, db, resetEmployee, signIn } from './helpers';

let app: INestApplication;
let clock: ClockService;

beforeAll(async () => {
  app = await createTestApp();
  clock = app.get(ClockService);
});
afterEach(() => clock.clearFakeTime());
afterAll(async () => {
  await app.close();
  await closeDb();
});

const cookieHeader = (res: request.Response): string[] => {
  const raw = res.headers['set-cookie'] as unknown;
  return Array.isArray(raw) ? (raw as string[]) : [];
};

describe('employee code + PIN sign-in', () => {
  const roles: Array<[string, string, string]> = [
    ['employee', DEMO.employee, 'EMPLOYEE'],
    ['dept admin', DEMO.deptAdminProduction, 'DEPT_ADMIN'],
    ['HC admin', DEMO.hcAdmin, 'HC_ADMIN'],
    ['system admin', DEMO.sysAdmin, 'SYS_ADMIN'],
  ];

  it.each(roles)('all 4 roles can sign in: %s', async (_label, code, role) => {
    await resetEmployee(code);
    const res = await request(app.getHttpServer())
      .post('/api/auth/pin/login')
      .send({ employeeCode: code, pin: INITIAL_PIN })
      .expect(200);
    expect(res.body.user.employeeCode).toBe(code);
    expect(res.body.user.roles).toEqual(expect.arrayContaining(['EMPLOYEE', role]));
    // First sign-in: the seeded PIN is temporary.
    expect(res.body.user.pinMustChange).toBe(true);
  });

  it('sets an httpOnly session cookie and never stores the raw token', async () => {
    await resetEmployee(DEMO.employee);
    const res = await request(app.getHttpServer())
      .post('/api/auth/pin/login')
      .send({ employeeCode: DEMO.employee, pin: INITIAL_PIN })
      .expect(200);
    const cookie = cookieHeader(res).find((c) => c.startsWith(`${SESSION_COOKIE}=`));
    expect(cookie).toMatch(/HttpOnly/i);
    expect(cookie).toMatch(/SameSite=Lax/i);
    const token = /meal_session=([^;]+)/.exec(cookie ?? '')?.[1] ?? '';
    const rows = await db().authSession.findMany({
      where: { employee: { employeeCode: DEMO.employee } },
    });
    expect(rows).toHaveLength(1);
    expect(rows[0]?.tokenHash).not.toBe(token);
    expect(rows[0]?.tokenHash).toHaveLength(64);
  });

  it('rejects a wrong PIN and an unknown code with the same response', async () => {
    await resetEmployee(DEMO.employee);
    const wrongPin = await request(app.getHttpServer())
      .post('/api/auth/pin/login')
      .send({ employeeCode: DEMO.employee, pin: '000000' })
      .expect(401);
    const unknown = await request(app.getHttpServer())
      .post('/api/auth/pin/login')
      .send({ employeeCode: 'NOPE', pin: '000000' })
      .expect(401);
    expect(wrongPin.body).toEqual(unknown.body);
    expect(wrongPin.body.code).toBe('INVALID_CREDENTIALS');
  });

  it('rejects a malformed PIN with a validation error', async () => {
    const res = await request(app.getHttpServer())
      .post('/api/auth/pin/login')
      .send({ employeeCode: DEMO.employee, pin: '12ab' })
      .expect(400);
    expect(res.body.code).toBe('VALIDATION_FAILED');
  });

  it('locks the account after 5 wrong PINs and unlocks after 15 minutes', async () => {
    const code = 'PNJP000030';
    await resetEmployee(code);
    clock.setFakeTime(new Date('2026-10-19T08:00:00+07:00'));
    const attempt = (pin: string) =>
      request(app.getHttpServer()).post('/api/auth/pin/login').send({ employeeCode: code, pin });

    for (let i = 0; i < 4; i++) await attempt('000000').expect(401);
    const fifth = await attempt('000000').expect(423);
    expect(fifth.body.code).toBe('PIN_LOCKED');
    expect(fifth.body.lockedUntil).toBe(new Date('2026-10-19T08:15:00+07:00').toISOString());

    // Even the correct PIN is refused while locked.
    await attempt(INITIAL_PIN).expect(423);

    clock.setFakeTime(new Date('2026-10-19T08:15:00+07:00'));
    await attempt(INITIAL_PIN).expect(200);
    const row = await db().employee.findUniqueOrThrow({ where: { employeeCode: code } });
    expect(row.pinFailedCount).toBe(0);
    expect(row.lockedUntil).toBeNull();
  });

  it('a successful sign-in resets the wrong-attempt counter', async () => {
    const code = 'PNJP000031';
    await resetEmployee(code);
    for (let i = 0; i < 3; i++) {
      await request(app.getHttpServer())
        .post('/api/auth/pin/login')
        .send({ employeeCode: code, pin: '000000' })
        .expect(401);
    }
    await request(app.getHttpServer())
      .post('/api/auth/pin/login')
      .send({ employeeCode: code, pin: INITIAL_PIN })
      .expect(200);
    expect(
      (await db().employee.findUniqueOrThrow({ where: { employeeCode: code } })).pinFailedCount,
    ).toBe(0);
  });

  it('expired sessions stop working', async () => {
    await resetEmployee(DEMO.employee, { pinMustChange: false });
    // Anchor fake time at the real now so the browser-side cookie jar does not expire it first.
    const start = new Date();
    clock.setFakeTime(start);
    const agent = await signIn(app, DEMO.employee);
    await agent.get('/api/auth/me').expect(200);
    clock.setFakeTime(new Date(start.getTime() + 11 * 3_600_000));
    await agent.get('/api/auth/me').expect(200);
    clock.setFakeTime(new Date(start.getTime() + 13 * 3_600_000)); // TTL is 12h
    await agent.get('/api/auth/me').expect(401);
  });
});

describe('forced PIN change', () => {
  const code = DEMO.employee;

  it('blocks everything except change-PIN, whoami and sign-out until the PIN is changed', async () => {
    await resetEmployee(code);
    const agent = await signIn(app, code);

    const me = await agent.get('/api/auth/me').expect(200);
    expect(me.body.user.pinMustChange).toBe(true);

    const blocked = await agent.get('/api/employees').expect(403);
    expect(blocked.body.code).toBe('PIN_CHANGE_REQUIRED');

    await agent
      .post('/api/auth/pin/change')
      .send({ currentPin: INITIAL_PIN, newPin: '654321' })
      .expect(204);

    expect((await agent.get('/api/auth/me').expect(200)).body.user.pinMustChange).toBe(false);
    // Employee role has no access to the finder, but it is now a normal 403, not a PIN block.
    expect((await agent.get('/api/employees').expect(403)).body.code).toBe('FORBIDDEN');
  });

  it('the old PIN stops working and the new one signs in', async () => {
    await resetEmployee(code);
    const agent = await signIn(app, code);
    await agent
      .post('/api/auth/pin/change')
      .send({ currentPin: INITIAL_PIN, newPin: '654321' })
      .expect(204);
    await request(app.getHttpServer())
      .post('/api/auth/pin/login')
      .send({ employeeCode: code, pin: INITIAL_PIN })
      .expect(401);
    const again = await request(app.getHttpServer())
      .post('/api/auth/pin/login')
      .send({ employeeCode: code, pin: '654321' })
      .expect(200);
    expect(again.body.user.pinMustChange).toBe(false);
  });

  it('rejects a wrong current PIN, an unchanged PIN and malformed input', async () => {
    await resetEmployee(code);
    const agent = await signIn(app, code);
    await agent
      .post('/api/auth/pin/change')
      .send({ currentPin: '999999', newPin: '654321' })
      .expect(401);
    await agent
      .post('/api/auth/pin/change')
      .send({ currentPin: INITIAL_PIN, newPin: INITIAL_PIN })
      .expect(400);
    await agent
      .post('/api/auth/pin/change')
      .send({ currentPin: INITIAL_PIN, newPin: '12' })
      .expect(400);
  });

  it("changing the PIN signs out the employee's other sessions and writes an audit entry", async () => {
    await resetEmployee(code);
    const first = await signIn(app, code);
    const second = await signIn(app, code);
    await second
      .post('/api/auth/pin/change')
      .send({ currentPin: INITIAL_PIN, newPin: '654321' })
      .expect(204);
    await first.get('/api/auth/me').expect(401);
    await second.get('/api/auth/me').expect(200);
    const entry = await db().auditLog.findFirst({
      where: { action: 'auth.pin.changed' },
      orderBy: { at: 'desc' },
    });
    expect(entry).toMatchObject({ entityType: 'Employee' });
    expect(JSON.stringify(entry)).not.toContain('654321');
  });

  it('sign-out ends the session', async () => {
    await resetEmployee(code, { pinMustChange: false });
    const agent = await signIn(app, code);
    await agent.post('/api/auth/logout').expect(204);
    await agent.get('/api/auth/me').expect(401);
  });
});

describe('PIN reset', () => {
  it('issues a temporary PIN, forces a change and ends open sessions', async () => {
    const target = 'PNJP000040';
    await resetEmployee(target, { pin: '777777', pinMustChange: false });
    const victim = await signIn(app, target, '777777');

    await resetEmployee(DEMO.hcAdmin, { pinMustChange: false });
    const hc = await signIn(app, DEMO.hcAdmin);
    const res = await hc
      .post(`/api/employees/${target}/pin/reset`)
      .send({ reason: 'Quên PIN' })
      .expect(200);
    expect(res.body.temporaryPin).toMatch(/^\d{6}$/);

    await victim.get('/api/auth/me').expect(401);
    await request(app.getHttpServer())
      .post('/api/auth/pin/login')
      .send({ employeeCode: target, pin: '777777' })
      .expect(401);
    const login = await request(app.getHttpServer())
      .post('/api/auth/pin/login')
      .send({ employeeCode: target, pin: res.body.temporaryPin })
      .expect(200);
    expect(login.body.user.pinMustChange).toBe(true);

    const entry = await db().auditLog.findFirst({
      where: { action: 'employee.pin.reset', entityId: login.body.user.id },
      orderBy: { at: 'desc' },
    });
    expect(entry).toMatchObject({ reason: 'Quên PIN' });
    expect(JSON.stringify(entry)).not.toContain(res.body.temporaryPin);
  });

  it('clears a lock so the employee can sign in with the temporary PIN', async () => {
    const target = 'PNJP000041';
    await resetEmployee(target);
    for (let i = 0; i < 5; i++) {
      await request(app.getHttpServer())
        .post('/api/auth/pin/login')
        .send({ employeeCode: target, pin: '000000' });
    }
    await resetEmployee(DEMO.hcAdmin, { pinMustChange: false });
    const hc = await signIn(app, DEMO.hcAdmin);
    const res = await hc.post(`/api/employees/${target}/pin/reset`).send({}).expect(200);
    await request(app.getHttpServer())
      .post('/api/auth/pin/login')
      .send({ employeeCode: target, pin: res.body.temporaryPin })
      .expect(200);
  });
});

describe('Entra ID SSO (mock mode)', () => {
  async function ssoStart() {
    const res = await request(app.getHttpServer()).get('/api/auth/entra/login').expect(302);
    const location = res.headers.location as string;
    const state = new URL(location, 'http://x').searchParams.get('state') ?? '';
    const txCookie =
      cookieHeader(res)
        .find((c) => c.startsWith('meal_oidc='))
        ?.split(';')[0] ?? '';
    return { location, state, txCookie };
  }

  it('maps the email case-insensitively and signs in without a PIN change', async () => {
    await resetEmployee(DEMO.employee); // PIN is still temporary
    const { location, state, txCookie } = await ssoStart();
    expect(location).toContain('/api/auth/entra/mock-authorize');

    const cb = await request(app.getHttpServer())
      .get('/api/auth/entra/callback')
      .query({ state, mock_email: 'AN.Nguyen@PNJP.test' })
      .set('Cookie', txCookie)
      .expect(302);
    expect(cb.headers.location).toBe('/today');

    const session =
      cookieHeader(cb)
        .find((c) => c.startsWith(`${SESSION_COOKIE}=`))
        ?.split(';')[0] ?? '';
    const me = await request(app.getHttpServer())
      .get('/api/auth/me')
      .set('Cookie', session)
      .expect(200);
    expect(me.body.user.employeeCode).toBe(DEMO.employee);
    // SSO sessions are not asked for a PIN.
    expect(me.body.user.pinMustChange).toBe(false);
  });

  it('sends an unknown email to the "account not linked" screen', async () => {
    const { state, txCookie } = await ssoStart();
    const cb = await request(app.getHttpServer())
      .get('/api/auth/entra/callback')
      .query({ state, mock_email: 'nobody@elsewhere.test' })
      .set('Cookie', txCookie)
      .expect(302);
    expect(cb.headers.location).toBe('/auth/not-linked');
    expect(
      cookieHeader(cb).some(
        (c) => c.startsWith(`${SESSION_COOKIE}=`) && !/Expires=Thu, 01 Jan 1970/.test(c),
      ),
    ).toBe(false);
  });

  it('rejects a callback without the signed transaction cookie, or with a wrong state', async () => {
    const { state, txCookie } = await ssoStart();
    const noCookie = await request(app.getHttpServer())
      .get('/api/auth/entra/callback')
      .query({ state, mock_email: 'an.nguyen@pnjp.test' })
      .expect(302);
    expect(noCookie.headers.location).toBe('/login?error=sso_failed');

    const wrongState = await request(app.getHttpServer())
      .get('/api/auth/entra/callback')
      .query({ state: 'forged', mock_email: 'an.nguyen@pnjp.test' })
      .set('Cookie', txCookie)
      .expect(302);
    expect(wrongState.headers.location).toBe('/login?error=sso_failed');

    const tampered = txCookie.replace(/\.[^.]*$/, '.AAAA');
    const bad = await request(app.getHttpServer())
      .get('/api/auth/entra/callback')
      .query({ state, mock_email: 'an.nguyen@pnjp.test' })
      .set('Cookie', tampered)
      .expect(302);
    expect(bad.headers.location).toBe('/login?error=sso_failed');
  });

  it('serves the fake Microsoft page only in mock mode', async () => {
    await request(app.getHttpServer()).get('/api/auth/entra/mock-authorize?state=abc').expect(200);
  });
});

describe('fake-time endpoint', () => {
  it('lets only a system admin freeze time, and audits it', async () => {
    await resetEmployee(DEMO.sysAdmin, { pinMustChange: false });
    await resetEmployee(DEMO.hcAdmin, { pinMustChange: false });
    const sys = await signIn(app, DEMO.sysAdmin);
    const hc = await signIn(app, DEMO.hcAdmin);

    // Stay within the session lifetime: sessions are judged against the fake clock too.
    const at = new Date(Date.now() + 3_600_000).toISOString();
    await hc.put('/api/dev/clock').send({ at }).expect(403);

    const res = await sys.put('/api/dev/clock').send({ at }).expect(200);
    expect(res.body).toEqual({ fake: true, now: at });
    expect((await sys.get('/api/dev/clock').expect(200)).body.fake).toBe(true);
    expect(await db().auditLog.count({ where: { action: 'dev.clock.set' } })).toBeGreaterThan(0);

    const cleared = await sys.put('/api/dev/clock').send({ at: null }).expect(200);
    expect(cleared.body.fake).toBe(false);
  });
});

describe('production safety', () => {
  it('has no fake-time route when fake time is not enabled', async () => {
    const prodLike = await createTestApp({ CLOCK_FAKE_ENABLED: 'false' });
    try {
      await resetEmployee(DEMO.sysAdmin, { pinMustChange: false });
      const sys = await signIn(prodLike, DEMO.sysAdmin);
      await sys.get('/api/dev/clock').expect(404);
    } finally {
      await prodLike.close();
    }
  });

  it('keeps the mock Microsoft page out of real-OIDC mode', async () => {
    const env = {
      ENTRA_MODE: 'oidc',
      ENTRA_TENANT_ID: 'tenant',
      ENTRA_CLIENT_ID: 'client',
      ENTRA_CLIENT_SECRET: 'secret',
      ENTRA_REDIRECT_URI: 'https://meal.example.test/api/auth/entra/callback',
    };
    const oidcApp = await createTestApp(env);
    try {
      await request(oidcApp.getHttpServer())
        .get('/api/auth/entra/mock-authorize?state=abc')
        .expect(404);
    } finally {
      await oidcApp.close();
    }
  });
});
