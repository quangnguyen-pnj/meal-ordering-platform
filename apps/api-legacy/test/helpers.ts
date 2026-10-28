import type { INestApplication, Type } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { PrismaClient } from '@prisma/client';
import * as argon2 from 'argon2';
import request from 'supertest';
import { vnToUtc } from '@meal/shared';
import { AppModule } from '../src/app.module';
import { configureApp } from '../src/app.setup';
import { ClockService } from '../src/clock/clock.service';
import { loadEnv, type AppEnv } from '../src/config/env';
import { DEMO } from '../src/seed-data';

export const INITIAL_PIN = '123456';
export { DEMO };

export function testEnv(overrides: Record<string, string | undefined> = {}): AppEnv {
  return loadEnv({
    NODE_ENV: 'test',
    DATABASE_URL: process.env.DATABASE_URL,
    SESSION_SECRET: 'test-secret-test-secret-test-secret-1234',
    CLOCK_FAKE_ENABLED: 'true',
    JOBS_ENABLED: 'false',
    ENTRA_MODE: 'mock',
    DMP_MODE: 'mock',
    ...overrides,
  });
}

export async function createTestApp(
  overrides: Record<string, string | undefined> = {},
  extraControllers: Type<unknown>[] = [],
): Promise<INestApplication> {
  const moduleRef = await Test.createTestingModule({
    imports: [AppModule.forRoot(testEnv(overrides))],
    controllers: extraControllers,
  }).compile();
  const app = moduleRef.createNestApplication();
  configureApp(app);
  await app.init();
  return app;
}

let sharedPrisma: PrismaClient | undefined;
export function db(): PrismaClient {
  sharedPrisma ??= new PrismaClient();
  return sharedPrisma;
}
export async function closeDb(): Promise<void> {
  await sharedPrisma?.$disconnect();
  sharedPrisma = undefined;
}

/** Puts an employee back into the seeded state: initial PIN, change required, no lock. */
export async function resetEmployee(
  employeeCode: string,
  state: { pin?: string; pinMustChange?: boolean } = {},
): Promise<void> {
  const pin = state.pin ?? INITIAL_PIN;
  const employee = await db().employee.update({
    where: { employeeCode },
    data: {
      pinHash: await argon2.hash(pin, { type: argon2.argon2id }),
      pinMustChange: state.pinMustChange ?? true,
      pinFailedCount: 0,
      lockedUntil: null,
    },
  });
  await db().authSession.deleteMany({ where: { employeeId: employee.id } });
}

export type Agent = ReturnType<typeof request.agent>;

/** Signs in with employee code + PIN and returns a cookie-carrying agent. */
export async function signIn(
  app: INestApplication,
  employeeCode: string,
  pin = INITIAL_PIN,
): Promise<Agent> {
  const agent = request.agent(app.getHttpServer());
  await agent.post('/api/auth/pin/login').send({ employeeCode, pin }).expect(200);
  return agent;
}

/**
 * Seeds the employee with a known non-temporary PIN and signs them in, so tests of other
 * features are not blocked by the forced PIN change.
 */
export async function signInReady(app: INestApplication, employeeCode: string): Promise<Agent> {
  await resetEmployee(employeeCode, { pinMustChange: false });
  return signIn(app, employeeCode);
}

/**
 * Freezes the app's clock at a Vietnam wall time. Sessions age with fake time (P0 decision), so
 * sign in again after moving the clock more than SESSION_TTL_HOURS.
 */
export function setVnTime(app: INestApplication, date: string, time: string): Date {
  const at = vnToUtc(date, time);
  app.get(ClockService).setFakeTime(at);
  return at;
}

export interface Endpoint {
  method: 'get' | 'post' | 'put' | 'patch';
  url: string;
  body?: object;
}

/** Plan 7.1 rule 5: every admin endpoint has a 403 test. Asserts 403 for each agent. */
export async function expectForbidden(agents: Agent[], endpoints: Endpoint[]): Promise<void> {
  const failures: string[] = [];
  for (const agent of agents) {
    for (const e of endpoints) {
      const req = agent[e.method](e.url);
      const res = await (e.body ? req.send(e.body) : req);
      if (res.status !== 403) failures.push(`${e.method.toUpperCase()} ${e.url} -> ${res.status}`);
    }
  }
  expect(failures).toEqual([]);
}
