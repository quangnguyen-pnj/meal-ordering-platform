import { addDays, vnToUtc } from '@meal/shared';
import { buildEmployees, buildOrgTree, SEED_EMPLOYEE_COUNT } from '../../seed-data';
import {
  DmpError,
  type DmpAttendanceRecord,
  type DmpClient,
  type DmpEmployee,
  type DmpLeaveDayType,
  type DmpLeaveRecord,
  type DmpOrgNode,
} from '../dmp.client';
import rawScenarios from './scenarios.json';

interface Selector {
  employees?: string[];
  range?: [string, string];
}
interface Scenario {
  description: string;
  defaultCheckIn?: string;
  presentRatio?: number;
  error?: string;
  overrides?: Array<Selector & { checkIn: string | null }>;
  leave?: Array<Selector & { dayType: DmpLeaveDayType; leaveTypeName: string }>;
}

const SCENARIOS = rawScenarios.scenarios as unknown as Record<string, Scenario>;

export const MOCK_SCENARIO_NAMES = Object.keys(SCENARIOS);

function matches(sel: Selector, code: string): boolean {
  if (sel.employees?.includes(code)) return true;
  // Codes are fixed-width ("PNJP000008"), so string comparison orders them correctly.
  return sel.range !== undefined && code >= sel.range[0] && code <= sel.range[1];
}

/**
 * Serves the deterministic seed org tree and employees, plus attendance / leave generated from a
 * named scenario. Used whenever DMP_MODE=mock: development, tests and phases that run before a
 * DMP service account exists.
 */
export class MockDmpClient implements DmpClient {
  private readonly scenario: Scenario;

  constructor(scenarioName: string) {
    const scenario = SCENARIOS[scenarioName];
    if (!scenario) {
      throw new Error(
        `Unknown DMP mock scenario "${scenarioName}". Available: ${MOCK_SCENARIO_NAMES.join(', ')}`,
      );
    }
    this.scenario = scenario;
  }

  async getOrgTree(): Promise<DmpOrgNode[]> {
    const nodes = buildOrgTree();
    const codeById = new Map(nodes.map((n) => [n.id, n.dmpCode]));
    return nodes.map((n) => ({
      code: n.dmpCode,
      name: n.name,
      parentCode: n.parentId ? (codeById.get(n.parentId) ?? null) : null,
      levelName: n.dmpLevelName,
      costCenterCode: null,
    }));
  }

  async getEmployees(): Promise<DmpEmployee[]> {
    const nodes = buildOrgTree();
    const codeById = new Map(nodes.map((n) => [n.id, n.dmpCode]));
    return buildEmployees(nodes).map((e) => ({
      employeeCode: e.employeeCode,
      name: e.name,
      email: e.email,
      orgUnitCode: codeById.get(e.orgUnitId) ?? '',
      costCenterCode: e.dmpCostCenterCode,
      startDate: e.startDate,
      endDate: null,
    }));
  }

  async getAttendance(date: string): Promise<DmpAttendanceRecord[]> {
    this.failIfScenarioErrors();
    const codes = (await this.getEmployees()).map((e) => e.employeeCode);
    const limit =
      this.scenario.presentRatio === undefined
        ? codes.length
        : Math.floor(SEED_EMPLOYEE_COUNT * this.scenario.presentRatio);
    const records: DmpAttendanceRecord[] = [];
    for (const code of codes.slice(0, limit)) {
      const override = this.scenario.overrides?.find((o) => matches(o, code));
      const time = override ? override.checkIn : (this.scenario.defaultCheckIn ?? '07:45');
      if (time === null || time === undefined) continue;
      records.push({ employeeCode: code, checkInAt: vnToUtc(date, time) });
    }
    return records;
  }

  async getLeave(fromDate: string, toDate: string): Promise<DmpLeaveRecord[]> {
    this.failIfScenarioErrors();
    const codes = (await this.getEmployees()).map((e) => e.employeeCode);
    const records: DmpLeaveRecord[] = [];
    for (let day = fromDate; day <= toDate; day = addDays(day, 1)) {
      for (const code of codes) {
        const leave = this.scenario.leave?.find((l) => matches(l, code));
        if (leave) {
          records.push({
            employeeCode: code,
            date: day,
            dayType: leave.dayType,
            leaveTypeName: leave.leaveTypeName,
          });
        }
      }
    }
    return records;
  }

  private failIfScenarioErrors(): void {
    if (this.scenario.error) throw new DmpError('API_ERROR', this.scenario.error);
  }
}
