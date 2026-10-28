import { buildDmpTree, toVnDateTime } from '@meal/shared';
import { SEED_EMPLOYEE_COUNT } from '../../seed-data';
import { DmpError } from '../dmp.client';
import { HttpDmpClient } from '../http-dmp.client';
import { loadEnv } from '../../config/env';
import { MOCK_SCENARIO_NAMES, MockDmpClient } from './mock-dmp.client';

const DAY = '2026-10-19';
const hhmm = (d: Date) => {
  const vn = toVnDateTime(d);
  return `${String(vn.hour).padStart(2, '0')}:${String(vn.minute).padStart(2, '0')}`;
};

describe('MockDmpClient', () => {
  it('ships every scenario the plan lists', () => {
    expect(MOCK_SCENARIO_NAMES).toEqual(
      expect.arrayContaining([
        'on-time',
        'late',
        'forgot-to-check-in',
        'morning-leave',
        'afternoon-leave',
        'api-error',
        'too-little-data',
      ]),
    );
  });

  it('rejects an unknown scenario with the list of valid ones', () => {
    expect(() => new MockDmpClient('nope')).toThrow(/Available: on-time/);
  });

  it('serves the seed org tree and 1,700 employees', async () => {
    const dmp = new MockDmpClient('on-time');
    const [tree, employees] = await Promise.all([dmp.getOrgTree(), dmp.getEmployees()]);
    expect(tree.filter((n) => n.parentCode === null)).toHaveLength(1);
    // BR-61: the tree the sync builds from it has 6 levels.
    const built = buildDmpTree(tree);
    expect(built.ok && Math.max(...built.nodes.map((n) => n.level))).toBe(6);
    expect(employees).toHaveLength(SEED_EMPLOYEE_COUNT);
  });

  it('on-time: everyone checks in at 07:45 Vietnam time', async () => {
    const records = await new MockDmpClient('on-time').getAttendance(DAY);
    expect(records).toHaveLength(SEED_EMPLOYEE_COUNT);
    expect(new Set(records.map((r) => hhmm(r.checkInAt)))).toEqual(new Set(['07:45']));
  });

  it('late: the affected range checks in after the 08:10 cutoff', async () => {
    const records = await new MockDmpClient('late').getAttendance(DAY);
    const byCode = new Map(records.map((r) => [r.employeeCode, hhmm(r.checkInAt)]));
    expect(byCode.get('PNJP000008')).toBe('08:15');
    expect(byCode.get('PNJP000007')).toBe('07:45');
  });

  it('forgot-to-check-in: the affected range has no record', async () => {
    const records = await new MockDmpClient('forgot-to-check-in').getAttendance(DAY);
    expect(records).toHaveLength(SEED_EMPLOYEE_COUNT - 13);
    expect(records.some((r) => r.employeeCode === 'PNJP000010')).toBe(false);
  });

  it('too-little-data: only 30% of employees have attendance', async () => {
    const records = await new MockDmpClient('too-little-data').getAttendance(DAY);
    expect(records).toHaveLength(Math.floor(SEED_EMPLOYEE_COUNT * 0.3));
  });

  it('morning and afternoon leave produce AM and PM flags for every day in range', async () => {
    const am = await new MockDmpClient('morning-leave').getLeave('2026-10-19', '2026-10-20');
    const pm = await new MockDmpClient('afternoon-leave').getLeave('2026-10-19', '2026-10-19');
    expect(am).toHaveLength(13 * 2);
    expect(new Set(am.map((l) => l.dayType))).toEqual(new Set(['AM']));
    expect(new Set(pm.map((l) => l.dayType))).toEqual(new Set(['PM']));
  });

  it('api-error: attendance and leave calls fail with a DmpError', async () => {
    const dmp = new MockDmpClient('api-error');
    await expect(dmp.getAttendance(DAY)).rejects.toBeInstanceOf(DmpError);
    await expect(dmp.getLeave(DAY, DAY)).rejects.toBeInstanceOf(DmpError);
  });
});

describe('HttpDmpClient', () => {
  const env = loadEnv({
    DATABASE_URL: 'postgresql://x:x@localhost:5432/x',
    SESSION_SECRET: 'test-secret-test-secret-test-secret-1234',
    DMP_MODE: 'http',
    DMP_BASE_URL: 'http://dmp.invalid',
    DMP_SERVICE_EMAIL: 'svc@example.test',
    DMP_SERVICE_PASSWORD: 'x',
    DMP_COMPANY_CODE: 'PNJP',
  });
  const json = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

  afterEach(() => jest.restoreAllMocks());

  it('leaves attendance and leave to P3', async () => {
    const http = new HttpDmpClient(env);
    await expect(http.getAttendance(DAY)).rejects.toThrow(/P3/);
  });

  it('logs in, then reads /api/hierarchy into a flat list of nodes with parents', async () => {
    const calls: string[] = [];
    jest.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = String(input);
      calls.push(url);
      if (url.endsWith('/api/auth/login')) return json({ token: 't' });
      return json({
        code: 'ROOT',
        name: 'PNJP',
        levelName: 'Công ty',
        children: [{ code: 'A', name: 'Khối A', levelName: 'Khối', children: [] }],
      });
    });
    const tree = await new HttpDmpClient(env).getOrgTree();
    expect(tree).toEqual([
      { code: 'ROOT', name: 'PNJP', parentCode: null, levelName: 'Công ty', costCenterCode: null },
      { code: 'A', name: 'Khối A', parentCode: 'ROOT', levelName: 'Khối', costCenterCode: null },
    ]);
    expect(calls).toEqual([
      'http://dmp.invalid/api/auth/login',
      'http://dmp.invalid/api/hierarchy',
    ]);
  });

  it('pages through /api/hr-employees sequentially and drops personal fields', async () => {
    const pages = [
      {
        content: [
          {
            employeeId: 'E1',
            fullName: 'Nguyễn A',
            email: 'a@x.test',
            unitCode: 'A',
            birthDate: '1990-01-01',
            phoneNumber: '0900',
            startWDate: '15-03-2020',
          },
        ],
        last: false,
      },
      { content: [{ employeeId: 'E2', name: 'Trần B', unitCode: 'A' }], last: true },
    ];
    const urls: string[] = [];
    jest.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = String(input);
      if (url.endsWith('/api/auth/login')) return json({ token: 't' });
      urls.push(url);
      return json(pages[urls.length - 1]);
    });
    const employees = await new HttpDmpClient(env).getEmployees();
    expect(urls.map((u) => new URL(u).searchParams.get('page'))).toEqual(['0', '1']);
    expect(new URL(urls[0]!).searchParams.get('size')).toBe('500');
    expect(employees).toEqual([
      {
        employeeCode: 'E1',
        name: 'Nguyễn A',
        email: 'a@x.test',
        orgUnitCode: 'A',
        costCenterCode: null,
        startDate: '2020-03-15',
        endDate: null,
      },
      {
        employeeCode: 'E2',
        name: 'Trần B',
        email: null,
        orgUnitCode: 'A',
        costCenterCode: null,
        startDate: null,
        endDate: null,
      },
    ]);
    expect(JSON.stringify(employees)).not.toMatch(/1990|0900/);
  });
});
