import type { AppEnv } from '../config/env';
import { parseEmployeePage, parseHierarchy } from './dmp-parsers';
import {
  DmpError,
  type DmpAttendanceRecord,
  type DmpClient,
  type DmpEmployee,
  type DmpLeaveRecord,
  type DmpOrgNode,
} from './dmp.client';

const TIMEOUT_MS = 10_000;
const MAX_ATTEMPTS = 3;
const PAGE_SIZE = 500;
/** Safety stop: 1,700 employees fit in 4 pages; 200 pages means the paging is broken. */
const MAX_PAGES = 200;

/**
 * The real DMP client (plan section 5): service-token login and a request helper with the 10 s
 * timeout and 3 retries with backoff. Org tree and employees are implemented (P1); attendance and
 * leave follow in P3. Never used against a real DMP in tests: they run with DMP_MODE=mock or a
 * stubbed fetch.
 *
 * Endpoint notes for those phases:
 *   - GET /api/hr-employees  (page, size <= 500, sort=employeeId,asc), Spring Page, sequential pages.
 *   - GET /api/hierarchy     (full tree, unpaged).
 *   - GET /api/attendance    (fromDate, toDate as dd-MM-yyyy, companyCode); earliest check-in per person.
 *   - GET /api/daily-forecast (hasLeave=true, ISO dates); flags only (BR-35).
 *   - Never: /api/employee-view, /api/hr-employees/search/medic, generate-data, dateAttendanceForecast.
 */
export class HttpDmpClient implements DmpClient {
  private token: string | null = null;

  constructor(private readonly env: AppEnv) {}

  /** GET /api/hierarchy: the full tree, unpaged. */
  async getOrgTree(): Promise<DmpOrgNode[]> {
    return parseHierarchy(await this.get<unknown>('/api/hierarchy'));
  }

  /** GET /api/hr-employees: pages fetched one after another, size 500, sorted by employeeId. */
  async getEmployees(): Promise<DmpEmployee[]> {
    const all: DmpEmployee[] = [];
    for (let page = 0; page < MAX_PAGES; page++) {
      const query = new URLSearchParams({
        page: String(page),
        size: String(PAGE_SIZE),
        sort: 'employeeId,asc',
      });
      const { items, last } = parseEmployeePage(
        await this.get<unknown>(`/api/hr-employees?${query.toString()}`),
      );
      all.push(...items);
      if (last) return all;
    }
    throw new DmpError('API_ERROR', `DMP /api/hr-employees returned more than ${MAX_PAGES} pages`);
  }

  async getAttendance(_date: string): Promise<DmpAttendanceRecord[]> {
    return this.notImplemented('getAttendance', 'P3');
  }

  async getLeave(_fromDate: string, _toDate: string): Promise<DmpLeaveRecord[]> {
    return this.notImplemented('getLeave', 'P3');
  }

  /** POST /api/auth/login with the LDAP service account; the JWT has no refresh token. */
  protected async login(): Promise<string> {
    const res = await this.fetchWithTimeout('/api/auth/login', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        email: this.env.DMP_SERVICE_EMAIL,
        password: this.env.DMP_SERVICE_PASSWORD,
      }),
    });
    if (!res.ok) throw new DmpError('AUTH', `DMP login failed with status ${res.status}`);
    const body = (await res.json()) as { token?: string; accessToken?: string; jwt?: string };
    const token = body.token ?? body.accessToken ?? body.jwt;
    // TODO(P1): confirm the token field name against DMP /v3/api-docs (plan section 5).
    if (!token) throw new DmpError('AUTH', 'DMP login response held no token');
    this.token = token;
    return token;
  }

  /** GET with bearer token; re-logs in once on 401 and retries transient failures with backoff. */
  protected async get<T>(path: string): Promise<T> {
    let lastError: unknown;
    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        const token = this.token ?? (await this.login());
        let res = await this.fetchWithTimeout(path, {
          headers: { authorization: `Bearer ${token}` },
        });
        if (res.status === 401) {
          const fresh = await this.login();
          res = await this.fetchWithTimeout(path, {
            headers: { authorization: `Bearer ${fresh}` },
          });
        }
        if (!res.ok)
          throw new DmpError('API_ERROR', `DMP ${path} failed with status ${res.status}`);
        return (await res.json()) as T;
      } catch (error) {
        lastError = error;
        if (error instanceof DmpError && error.kind === 'AUTH') throw error;
        if (attempt < MAX_ATTEMPTS)
          await new Promise((r) => setTimeout(r, 500 * 2 ** (attempt - 1)));
      }
    }
    throw lastError instanceof Error ? lastError : new DmpError('API_ERROR', String(lastError));
  }

  private async fetchWithTimeout(path: string, init: RequestInit): Promise<Response> {
    const base = (this.env.DMP_BASE_URL ?? '').replace(/\/+$/, '');
    try {
      return await fetch(`${base}${path}`, { ...init, signal: AbortSignal.timeout(TIMEOUT_MS) });
    } catch (error) {
      if (error instanceof Error && error.name === 'TimeoutError') {
        throw new DmpError('TIMEOUT', `DMP ${path} timed out after ${TIMEOUT_MS} ms`);
      }
      throw new DmpError('API_ERROR', error instanceof Error ? error.message : String(error));
    }
  }

  private notImplemented(method: string, phase: string): never {
    throw new DmpError('NOT_IMPLEMENTED', `HttpDmpClient.${method} is implemented in ${phase}`);
  }
}
