import { Injectable } from '@nestjs/common';
import type { Prisma } from '@prisma/client';
import {
  adminDayWindow,
  dayLockAt,
  validateDaySelection,
  type ExtraMeal,
  type GridCellView,
  type GridDaySave,
  type GridSaveResult,
  type ListScope,
  type NotRegisteredView,
  type OrgFilterOption,
  type RegistrationGridView,
  type RegistrationProgressView,
  type RegistrationUnitOption,
} from '@meal/shared';
import { AuditService } from '../audit/audit.service';
import { ClockService } from '../clock/clock.service';
import { invalid, invalidState, notFound } from '../common/api-error';
import { employeeScopeWhere } from '../employees/employees.service';
import { loadOrg, orgFilterOptions, orgPathName, type OrgSnapshot } from '../org/org-index';
import { PrismaService } from '../prisma/prisma.service';
import { SettingsService } from '../settings/settings.service';
import { ForecastService } from './forecast.service';
import { assertMonday, dayLocked, isLeft } from './my-registrations.service';
import {
  RegistrationStore,
  activeQuantities,
  fromDb,
  sumQuantities,
  toDb,
  type EmployeeForRegistration,
  type LoadedWeek,
} from './registration-store';

const MAX_LIST = 1000;

/** Employees who can register into a canteen, i.e. not on the "unassigned" list (BR-60, BR-63). */
function registrable(e: EmployeeForRegistration): e is EmployeeForRegistration & {
  assignment: { canteenId: string; mealUnitId: string };
} {
  return e.status === 'ACTIVE' && e.assignment.canRegister && e.assignment.canteenId !== null;
}

/**
 * Dept and HC admins' side of registration: progress by unit, the not-registered list (BR-21),
 * the bulk-entry grid and paper forms (plan 4.1), and extra meals after the lock (BR-25).
 * Scope is checked by the guard; list queries are filtered by the guard's list scope.
 */
@Injectable()
export class AdminRegistrationsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly store: RegistrationStore,
    private readonly forecast: ForecastService,
    private readonly settings: SettingsService,
    private readonly audit: AuditService,
    private readonly clock: ClockService,
  ) {}

  /** Org nodes the caller may open a grid or a form for, with how many can register below. */
  async units(scope: ListScope, factoryId: string): Promise<RegistrationUnitOption[]> {
    const org = await loadOrg(this.prisma, factoryId);
    const employees = (
      await this.store.employees(this.prisma, factoryId, employeeScopeWhere(scope), org)
    ).filter(registrable);
    const counts = new Map<string, number>();
    for (const e of employees) {
      for (const id of org.byId.get(e.orgUnitId)?.path.split('/').filter(Boolean) ?? []) {
        counts.set(id, (counts.get(id) ?? 0) + 1);
      }
    }
    const inScope = (path: string) =>
      scope.kind !== 'paths' || scope.paths.some((p) => path.startsWith(p));
    return org.nodes
      .filter((n) => n.active && inScope(n.path) && (counts.get(n.id) ?? 0) > 0)
      .map((n) => ({
        id: n.id,
        name: n.name,
        pathName: orgPathName(org, n.id) || n.name,
        level: n.level,
        isMealUnit: n.isMealUnit,
        employeeCount: counts.get(n.id) ?? 0,
      }))
      .sort((a, b) => a.pathName.localeCompare(b.pathName, 'vi'));
  }

  /** Khối / Trung tâm / Phòng ban filter options within the caller's scope. */
  async orgUnits(scope: ListScope, factoryId: string): Promise<OrgFilterOption[]> {
    return orgFilterOptions(await loadOrg(this.prisma, factoryId), scope);
  }

  /** Meals per employee in the week (or one day) of each employee's own canteen menu. */
  private async mealsByEmployee(
    employees: ReadonlyArray<EmployeeForRegistration & { assignment: { canteenId: string } }>,
    weekStart: string,
    date: string | null,
  ): Promise<{ meals: Map<string, number>; weeks: Map<string, LoadedWeek | null> }> {
    const weeks = new Map<string, LoadedWeek | null>();
    for (const canteenId of new Set(employees.map((e) => e.assignment.canteenId))) {
      weeks.set(canteenId, await this.store.loadWeek(this.prisma, canteenId, weekStart));
    }
    const dates = date
      ? [date]
      : [...weeks.values()].flatMap((w) =>
          (w?.days ?? []).filter((d) => d.isServing).map((d) => d.date),
        );
    const rows = await this.prisma.registration.groupBy({
      by: ['employeeId'],
      where: {
        employeeId: { in: employees.map((e) => e.id) },
        date: { in: [...new Set(dates)].map(toDb) },
        status: 'ACTIVE',
        quantity: { gt: 0 },
      },
      _sum: { quantity: true },
    });
    return {
      meals: new Map(rows.map((r) => [r.employeeId, r._sum.quantity ?? 0])),
      weeks,
    };
  }

  private async scopedRegistrable(scope: ListScope, factoryId: string, weekStart: string) {
    const org = await loadOrg(this.prisma, factoryId);
    const where: Prisma.EmployeeWhereInput = {
      AND: [employeeScopeWhere(scope), { status: 'ACTIVE' }],
    };
    const employees = (await this.store.employees(this.prisma, factoryId, where, org))
      .filter(registrable)
      // BR-36: someone who left before the week starts has no meals to register.
      .filter((e) => !isLeft(e, weekStart));
    return { org, employees };
  }

  /** /admin/registrations: progress by meal unit for the week. */
  async progress(
    scope: ListScope,
    factoryId: string,
    weekStart: string,
  ): Promise<RegistrationProgressView> {
    assertMonday(weekStart);
    const { org, employees } = await this.scopedRegistrable(scope, factoryId, weekStart);
    const { meals, weeks } = await this.mealsByEmployee(employees, weekStart, null);
    const units = new Map<string, RegistrationProgressView['units'][number]>();
    for (const e of employees) {
      const id = e.assignment.mealUnitId;
      let u = units.get(id);
      if (!u) {
        const week = weeks.get(e.assignment.canteenId) ?? null;
        u = {
          mealUnitId: id,
          name: org.byId.get(id)?.name ?? id,
          pathName: orgPathName(org, id),
          canteenName: week?.canteenName ?? null,
          published: week?.published ?? false,
          headcount: 0,
          registered: 0,
          notRegistered: 0,
          meals: 0,
        };
        units.set(id, u);
      }
      const n = meals.get(e.id) ?? 0;
      u.headcount += 1;
      u.meals += n;
      if (n > 0) u.registered += 1;
      else u.notRegistered += 1;
    }
    return {
      weekStart,
      units: [...units.values()].sort((a, b) => a.pathName.localeCompare(b.pathName, 'vi')),
    };
  }

  /**
   * BR-21: employees in scope with no meal in the week (or on `date`), from the moment their
   * canteen's menu is published (registration opens, BR-02).
   */
  async notRegistered(
    scope: ListScope,
    factoryId: string,
    weekStart: string,
    date: string | null,
    orgUnitId: string | null = null,
  ): Promise<NotRegisteredView> {
    assertMonday(weekStart);
    const scoped = await this.scopedRegistrable(scope, factoryId, weekStart);
    const { org } = scoped;
    // Khối / Trung tâm / Phòng ban filter, on top of the guard's scope (never widens it).
    const under = orgUnitId ? org.byId.get(orgUnitId)?.path : undefined;
    const employees =
      orgUnitId === null
        ? scoped.employees
        : scoped.employees.filter(
            (e) => under !== undefined && org.byId.get(e.orgUnitId)?.path.startsWith(under),
          );
    const { meals, weeks } = await this.mealsByEmployee(employees, weekStart, date);
    const items = employees
      .filter((e) => {
        const week = weeks.get(e.assignment.canteenId);
        if (!week?.published) return false;
        if (date && !week.days.some((d) => d.date === date && d.isServing)) return false;
        if (date && isLeft(e, date)) return false;
        return (meals.get(e.id) ?? 0) === 0;
      })
      .map((e) => ({
        employeeCode: e.employeeCode,
        name: e.name,
        orgPath: orgPathName(org, e.orgUnitId),
        mealUnitName: org.byId.get(e.assignment.mealUnitId)?.name ?? '',
      }))
      .sort(
        (a, b) => a.orgPath.localeCompare(b.orgPath, 'vi') || a.name.localeCompare(b.name, 'vi'),
      );
    return { weekStart, date, total: items.length, items: items.slice(0, MAX_LIST) };
  }

  /** Employees below `orgUnitId` with their assignment; the guard has checked the node's scope. */
  private async unitEmployees(factoryId: string, orgUnitId: string, weekStart: string) {
    const org = await loadOrg(this.prisma, factoryId);
    const unit = org.byId.get(orgUnitId);
    if (!unit) throw notFound('Org unit not found');
    const all = await this.store.employees(
      this.prisma,
      factoryId,
      {
        orgUnit: { path: { startsWith: unit.path } },
        OR: [{ status: 'ACTIVE' }, { endDate: { gt: toDb(weekStart) } }],
      },
      org,
    );
    return { org, unit, all };
  }

  /**
   * Bulk-entry grid and printable forms (plan 4.1): employees below an org node, one column per
   * meal of each serving day. Employees of another canteen are counted, not shown, so every
   * row uses the same menu.
   */
  async grid(
    factoryId: string,
    orgUnitId: string,
    weekStart: string,
    canteenId: string | undefined,
  ): Promise<RegistrationGridView> {
    assertMonday(weekStart);
    await this.forecast.catchUp(factoryId);
    const { org, unit, all } = await this.unitEmployees(factoryId, orgUnitId, weekStart);
    const candidates = all.filter((e) => e.assignment.canRegister && e.assignment.canteenId);
    const canteenCounts = new Map<string, number>();
    for (const e of candidates) {
      canteenCounts.set(
        e.assignment.canteenId!,
        (canteenCounts.get(e.assignment.canteenId!) ?? 0) + 1,
      );
    }
    const canteenRows = await this.prisma.canteen.findMany({
      where: { id: { in: [...canteenCounts.keys()] } },
      select: { id: true, name: true },
      orderBy: { code: 'asc' },
    });
    const chosen =
      canteenId && canteenCounts.has(canteenId)
        ? canteenId
        : ([...canteenCounts.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] ?? null);
    const rowsOf = candidates.filter((e) => e.assignment.canteenId === chosen);
    const base: RegistrationGridView = {
      state: 'NO_EMPLOYEES',
      orgUnit: { id: unit.id, name: unit.name, pathName: orgPathName(org, unit.id) || unit.name },
      weekStart,
      canteens: canteenRows,
      canteenId: chosen,
      canteenName: canteenRows.find((c) => c.id === chosen)?.name ?? null,
      closeAt: null,
      forecastTaken: false,
      days: [],
      rows: [],
      skipped: {
        otherCanteen: candidates.length - rowsOf.length,
        cannotRegister: all.filter((e) => e.status === 'ACTIVE' && !e.assignment.canRegister)
          .length,
      },
    };
    if (!chosen || rowsOf.length === 0) return base;
    const week = await this.store.loadWeek(this.prisma, chosen, weekStart);
    if (!week?.published) return { ...base, state: 'NOT_PUBLISHED' };

    const now = this.clock.now();
    const lockTime = await this.settings.get(factoryId, 'day.lockTime');
    const serving = week.days.filter((d) => d.isServing);
    const ids = rowsOf.map((e) => e.id);
    const [regs, forecast] = await Promise.all([
      this.store.rowsOf(
        this.prisma,
        ids,
        serving.map((d) => d.date),
      ),
      week.closedAt ? this.store.forecastOf(this.prisma, week.id, ids) : null,
    ]);
    return {
      ...base,
      state: 'PUBLISHED',
      closeAt: week.closeAt!.toISOString(),
      forecastTaken: week.closedAt !== null,
      days: serving.map((d) => ({
        date: d.date,
        // TODO(BR-25): `finalized` comes from DayClose in P4.
        window: adminDayWindow({ now, date: d.date, lockTime, finalized: false }),
        lockAt: dayLockAt(d.date, lockTime).toISOString(),
        items: d.items.map((i) => ({ id: i.id, displayName: i.displayName, type: i.type })),
      })),
      rows: rowsOf.map((e) => {
        const cells: Record<string, GridCellView> = {};
        for (const d of serving) {
          const mine = regs.filter((r) => r.employeeId === e.id && fromDb(r.date) === d.date);
          cells[d.date] = {
            quantities: activeQuantities(mine),
            forecast: forecast ? (forecast.get(e.id)?.get(d.date) ?? {}) : null,
            extra: mine.some((r) => r.isExtra && r.status === 'ACTIVE' && r.quantity > 0),
            needsReselect: mine
              .filter((r) => r.status === 'NEEDS_RESELECT')
              .reduce((s, r) => s + r.quantity, 0),
            blocked: isLeft(e, d.date),
          };
        }
        return {
          employeeCode: e.employeeCode,
          name: e.name,
          orgPath: orgPathName(org, e.orgUnitId),
          cells,
        };
      }),
    };
  }

  /**
   * Saves one day of the grid as a whole batch (plan 4.1), on behalf of the employees, until
   * the day's lock (BR-21). Any invalid row rejects the batch with row-level errors. Every
   * changed employee-day is audited (BR-70).
   */
  async saveGridDay(
    actorId: string,
    factoryId: string,
    orgUnitId: string,
    date: string,
    input: GridDaySave,
  ): Promise<GridSaveResult> {
    return this.saveGridDays(actorId, factoryId, orgUnitId, input.canteenId, [
      { date, rows: input.rows },
    ]);
  }

  /**
   * The grid save for several days of one canteen's week at once (the Excel import): every day
   * is validated first, then all are written in one transaction, so a file is applied whole or
   * not at all. Same rules and audit as one grid day.
   */
  async saveGridDays(
    actorId: string,
    factoryId: string,
    orgUnitId: string,
    canteenId: string,
    days: ReadonlyArray<{ date: string; rows: GridDaySave['rows'] }>,
  ): Promise<GridSaveResult & { days: number }> {
    await this.forecast.closeDueWeeks(factoryId);
    const lockTime = await this.settings.get(factoryId, 'day.lockTime');
    const windowAt = (now: Date, date: string) =>
      adminDayWindow({ now, date, lockTime, finalized: false });
    const prepared: Array<{ date: string; rows: GridDaySave['rows'] }> = [];
    let week: LoadedWeek | null = null;
    let unitPath = '';
    let org: OrgSnapshot | null = null;
    const byCode = new Map<string, EmployeeForRegistration>();
    const errors: Array<{
      employeeCode: string;
      code: string;
      menuItemId?: string;
      date?: string;
    }> = [];

    for (const input of days) {
      const { date } = input;
      const found = await this.store.publishedDay(this.prisma, canteenId, date);
      if (!found || found.week.factoryId !== factoryId || (week && found.week.id !== week.id)) {
        throw invalidState('No published serving day for this canteen and date (BR-02)', {
          date,
        });
      }
      const day = found.day;
      if (!week) {
        week = found.week;
        const loaded = await this.unitEmployees(factoryId, orgUnitId, week.weekStart);
        org = loaded.org;
        unitPath = loaded.unit.path;
      }
      if (windowAt(this.clock.now(), date) !== 'ON_BEHALF') throw dayLocked(date);

      const missing = input.rows.map((r) => r.employeeCode).filter((c) => !byCode.has(c));
      if (missing.length > 0) {
        const found = await this.store.employees(
          this.prisma,
          factoryId,
          { employeeCode: { in: missing } },
          org!,
        );
        for (const e of found) byCode.set(e.employeeCode, e);
      }
      const itemIds = new Set(day.items.map((i) => i.id));
      const seen = new Set<string>();
      const where = days.length > 1 ? { date } : {};
      for (const row of input.rows) {
        const e = byCode.get(row.employeeCode);
        const hasMeals = row.lines.some((l) => l.quantity > 0);
        if (seen.has(row.employeeCode)) {
          errors.push({ employeeCode: row.employeeCode, code: 'DUPLICATE_ROW', ...where });
          continue;
        }
        seen.add(row.employeeCode);
        if (!e || !org!.byId.get(e.orgUnitId)?.path.startsWith(unitPath)) {
          errors.push({ employeeCode: row.employeeCode, code: 'NOT_IN_UNIT', ...where });
          continue;
        }
        if (!e.assignment.canRegister || e.assignment.canteenId !== canteenId) {
          errors.push({ employeeCode: row.employeeCode, code: 'CANNOT_REGISTER', ...where });
          continue;
        }
        if (hasMeals && isLeft(e, date)) {
          errors.push({ employeeCode: row.employeeCode, code: 'LEFT', ...where });
          continue;
        }
        const res = validateDaySelection(row.lines, itemIds);
        if (!res.ok)
          errors.push(
            ...res.errors.map((x) => ({ employeeCode: row.employeeCode, ...x, ...where })),
          );
      }
      prepared.push(input);
    }
    if (errors.length > 0) throw invalid('Invalid grid rows', errors);
    if (!week) return { changedEmployees: 0, meals: 0, days: 0 };
    const weekId = week.id;

    const changed = new Set<string>();
    let meals = 0;
    await this.prisma.$transaction(
      async (tx) => {
        const forecastTaken = await this.store.lockWeek(tx, weekId);
        for (const { date, rows } of prepared) {
          if (windowAt(this.clock.now(), date) !== 'ON_BEHALF') throw dayLocked(date);
          const ids = rows.map((r) => byCode.get(r.employeeCode)!.id);
          const existing = await this.store.rowsOf(tx, ids, [date]);
          for (const row of rows) {
            const e = byCode.get(row.employeeCode)!;
            const res = await this.store.applyDay(tx, {
              employeeId: e.id,
              date,
              lines: row.lines,
              existing: existing.filter((r) => r.employeeId === e.id),
              source: 'ADMIN',
            });
            meals += sumQuantities(res.after);
            if (!res.changed) continue;
            changed.add(e.id);
            await this.audit.log(
              {
                actorId,
                action: 'registration.onBehalf',
                entityType: 'RegistrationDay',
                entityId: `${e.employeeCode}:${date}`,
                before: { quantities: res.before },
                after: { quantities: res.after, menuWeekId: weekId, afterClose: forecastTaken },
              },
              tx,
            );
          }
        }
      },
      { timeout: 120_000 },
    );
    return { changedEmployees: changed.size, meals, days: prepared.length };
  }

  /**
   * BR-25: after the day's lock and before finalization, an admin adds meals flagged "Extra"
   * (new hires, forgotten registrations), with a required reason. Audited (BR-70).
   */
  async addExtra(actorId: string, factoryId: string, employeeCode: string, input: ExtraMeal) {
    const org: OrgSnapshot = await loadOrg(this.prisma, factoryId);
    const [e] = await this.store.employees(this.prisma, factoryId, { employeeCode }, org);
    if (!e) throw notFound('Employee not found');
    if (!e.assignment.canRegister || !e.assignment.canteenId) {
      throw invalidState('Employee has no valid meal unit (BR-60)');
    }
    if (isLeft(e, input.date)) throw invalidState('Employee has left (BR-36)');
    const found = await this.store.publishedDay(this.prisma, e.assignment.canteenId, input.date);
    if (!found) throw invalidState('No published serving day for this date (BR-02)');
    if (!found.day.items.some((i) => i.id === input.menuItemId)) {
      throw invalid('Invalid selection (BR-20)', [
        { code: 'ITEM_NOT_ON_MENU', menuItemId: input.menuItemId },
      ]);
    }
    const lockTime = await this.settings.get(factoryId, 'day.lockTime');
    const windowAt = (now: Date) =>
      // TODO(BR-25): `finalized` comes from DayClose in P4.
      adminDayWindow({ now, date: input.date, lockTime, finalized: false });
    const window = windowAt(this.clock.now());
    if (window === 'ON_BEHALF') {
      throw invalidState('Before the lock, register on behalf instead (BR-21)');
    }
    if (window === 'CLOSED') throw dayLocked(input.date);

    await this.forecast.closeDueWeeks(factoryId);
    return this.prisma.$transaction(async (tx) => {
      await this.store.lockWeek(tx, found.week.id);
      if (windowAt(this.clock.now()) !== 'EXTRA') throw dayLocked(input.date);
      const existing = (await this.store.rowsOf(tx, [e.id], [input.date])).find(
        (r) => r.menuItemId === input.menuItemId,
      );
      const res = await this.store.addExtra(tx, {
        employeeId: e.id,
        date: input.date,
        menuItemId: input.menuItemId,
        quantity: input.quantity,
        reason: input.reason,
        existing,
      });
      await this.audit.log(
        {
          actorId,
          action: 'registration.extraAdded',
          entityType: 'RegistrationDay',
          entityId: `${e.employeeCode}:${input.date}`,
          before: { menuItemId: input.menuItemId, quantity: res.before },
          after: { menuItemId: input.menuItemId, quantity: res.after, isExtra: true },
          reason: input.reason,
        },
        tx,
      );
      return { employeeCode: e.employeeCode, date: input.date, ...res };
    });
  }
}
