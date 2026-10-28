import type { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { DEMO, closeDb, createTestApp, signInReady } from './helpers';

/**
 * Security review of 2026-10-24 (docs/security.md): secrets never leave the API in a response,
 * and no API answer is cached by the browser or a proxy.
 */
let app: INestApplication;

beforeAll(async () => {
  app = await createTestApp();
});
afterAll(async () => {
  await app.close();
  await closeDb();
});

/** Field names and values that must never appear in a response body. */
const SECRET_PATTERN = /pinHash|tokenHash|\$argon2|SESSION_SECRET|CLIENT_SECRET|SERVICE_PASSWORD/i;

const WEEK = '2026-10-26';
const READS: string[] = [
  '/api/auth/me',
  '/api/employees',
  '/api/employees?q=PNJP',
  '/api/employees/org-units',
  '/api/admin/org/tree',
  '/api/admin/org/unassigned',
  '/api/admin/org/missing-cost-center',
  '/api/admin/registrations/org-units',
  `/api/admin/registrations/not-registered?weekStart=${WEEK}`,
  `/api/admin/registrations/units?weekStart=${WEEK}`,
  '/api/admin/settings',
  '/api/system/roles',
  '/api/system/sync-runs',
  '/api/team',
  `/api/registrations/me/week?weekStart=${WEEK}`,
];

describe('responses never carry secrets', () => {
  it.each([
    ['HC admin', DEMO.hcAdmin],
    ['system admin', DEMO.sysAdmin],
    ['dept admin', DEMO.deptAdminProduction],
  ])('%s: no PIN hash, token hash or configured secret in any read', async (_label, code) => {
    const agent = await signInReady(app, code);
    for (const url of READS) {
      const res = await agent.get(url);
      expect({ url, leak: SECRET_PATTERN.exec(res.text)?.[0] ?? null }).toEqual({
        url,
        leak: null,
      });
    }
  });

  it('a rejected sign-in does not echo the PIN back', async () => {
    const res = await request(app.getHttpServer())
      .post('/api/auth/pin/login')
      .send({ employeeCode: DEMO.employee, pin: '98765x' })
      .expect(400);
    expect(res.text).not.toContain('98765x');
  });
});

describe('no API answer is cached', () => {
  it('errors, reads and the temporary-PIN reset are no-store and nosniff', async () => {
    const anonymous = await request(app.getHttpServer()).get('/api/auth/me').expect(401);
    const hc = await signInReady(app, DEMO.hcAdmin);
    const read = await hc.get('/api/employees');
    const reset = await hc.post('/api/employees/PNJP000043/pin/reset').send({}).expect(200);
    for (const res of [anonymous, read, reset]) {
      expect(res.headers['cache-control']).toBe('no-store');
      expect(res.headers['x-content-type-options']).toBe('nosniff');
      expect(res.headers['x-powered-by']).toBeUndefined();
    }
  });
});
