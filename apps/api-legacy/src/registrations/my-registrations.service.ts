import { HttpStatus, Injectable } from '@nestjs/common';
import type { Prisma } from '@prisma/client';
import {
  addDays,
  employeeDayWindow,
  isoWeekdayOf,
  mealDisplayName,
  mondayOf,
  toVnDateTime,
  upcomingWeekStart,
  validateDaySelection,
  type HistoryDayView,
  type HistoryView,
  type MyDayRegistrationView,
  type MyRegistrationWeekView,
  type MyWeekSave,
  type SelectionLine,
  type TeamDayView,
} from '@meal/shared';
import { AuditService } from '../audit/audit.service';
import { ClockService } from '../clock/clock.service';
import { ApiException, invalid, invalidState, notFound } from '../common/api-error';
import { PrismaService } from '../prisma/prisma.service';
import type { RequestPrincipal } from '../rbac/rbac.types';
import { SettingsService } from '../settings/settings.service';
import { ForecastService } from './forecast.service';
import {
  REG_ROW_SELECT,
  RegistrationStore,
  SESSION,
  activeQuantities,
  fromDb,
  sameQuantities,
  toDb,
  type EmployeeForRegistration,
  type LoadedWeek,
} from './registration-store';

const MAX_HISTORY_DAYS = 92;

export const dayLocked = (date: string) =>
  new ApiException(HttpStatus.CONFLICT, 'DAY_LOCKED', 'Day is locked', { details: { date } });

export function assertMonday(weekStart: string): void {
  let monday = false;
  try {
    monday = isoWeekdayOf(weekStart) === 1;
  } catch {
    monday = false;
  }
  if (!monday) {
    throw invalid('weekStart must be a Monday', [{ path: 'weekStart', message: 'monday' }]);
  }
}

/**
 * An employee's own registration (BR-20 to BR-24), the /today part of it,
 * history, and the colleagues view (BR-27).
 */
@Injectable()
export class MyRegistrationsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly store: RegistrationStore,
    private readonly forecast: ForecastService,
    private readonly settings: SettingsService,
    private readonly audit: AuditService,
    private readonly clock: ClockService,
  ) {}

  /**
   * J1: the week currently open for registration is preselected: the first of the next two weeks
   * whose menu is published and still before closeAt, else next week.
   */
  async defaultWeekStart(p: RequestPrincipal): Promise<string> {
    const now = this.clock.now();
    const next = upcomingWeekStart(now);
    const me = await this.store.employee(this.prisma, p.factoryId, p.employeeId);
    const canteenId = me.assignment.canRegister ? me.assignment.canteenId : null;
    if (!canteenId) return next;
    for (const weekStart of [next, addDays(next, 7)]) {
      const week = await this.store.loadWeek(this.prisma, canteenId, weekStart);
      if (week?.published && week.closeAt && now.getTime() < week.closeAt.getTime()) {
        return weekStart;
      }
    }
    return next;
  }

  async week(p: RequestPrincipal, weekStart: string): Promise<MyRegistrationWeekView> {
    assertMonday(weekStart);
    await this.forecast.catchUp(p.factoryId);
    const me = await this.store.employee(this.prisma, p.factoryId, p.employeeId);
    if (!me.assignment.canRegister || !me.assignment.canteenId) {
      return { state: 'UNASSIGNED', weekStart };
    }
    const week = await this.store.loadWeek(this.prisma, me.assignment.canteenId, weekStart);
    if (!week?.published) {
      const canteen = await this.prisma.canteen.findUniqueOrThrow({
        where: { id: me.assignment.canteenId },
      });
      return { state: 'NOT_PUBLISHED', weekStart, canteenName: canteen.name };
    }
    const serving = week.days.filter((d) => d.isServing);
    const [rows, forecast, lockTime] = await Promise.all([
      this.rowsWithNames(
        me.id,
        serving.map((d) => d.date),
      ),
      week.closedAt ? this.store.forecastOf(this.prisma, week.id, [me.id]) : null,
      this.settings.get(p.factoryId, 'day.lockTime'),
    ]);
    const now = this.clock.now();
    const days = serving.map((day) =>
      this.store.dayView({
        week,
        day,
        rows: rows.filter((r) => fromDb(r.date) === day.date),
        forecast: forecast ? (forecast.get(me.id)?.get(day.date) ?? {}) : null,
        now,
        lockTime,
        endDate: me.endDate,
      }),
    );
    return {
      state: 'PUBLISHED',
      weekStart,
      canteenName: week.canteenName,
      closeAt: week.closeAt!.toISOString(),
      forecastTaken: week.closedAt !== null,
      days,
      totalMeals: days.reduce((s, d) => s + d.actualTotal, 0),
    };
  }

  async saveWeek(p: RequestPrincipal, input: MyWeekSave) {
    assertMonday(input.weekStart);
    const weekEnd = addDays(input.weekStart, 6);
    for (const d of input.days) {
      if (d.date < input.weekStart || d.date > weekEnd) {
        throw invalid('Day outside the week', [{ path: 'days', message: d.date }]);
      }
    }
    const changedDays = await this.save(p, input.weekStart, input.days);
    return { view: await this.week(p, input.weekStart), changedDays };
  }

  /** /today: one day (default today, Vietnam time). */
  async day(p: RequestPrincipal, date: string | undefined): Promise<MyDayRegistrationView> {
    const target = date ?? toVnDateTime(this.clock.now()).date;
    const view = await this.week(p, mondayOf(target));
    if (view.state === 'UNASSIGNED') return { state: 'UNASSIGNED', date: target };
    const day = view.state === 'PUBLISHED' ? view.days.find((d) => d.date === target) : undefined;
    if (view.state !== 'PUBLISHED' || !day) {
      const canteenName = view.canteenName;
      return { state: 'NO_MENU', date: target, canteenName };
    }
    return {
      state: 'DAY',
      date: target,
      canteenName: view.canteenName,
      closeAt: view.closeAt,
      forecastTaken: view.forecastTaken,
      day,
      now: this.clock.now().toISOString(),
    };
  }

  async saveDay(p: RequestPrincipal, date: string, lines: SelectionLine[]) {
    const changedDays = await this.save(p, mondayOf(date), [{ date, lines }]);
    return { view: await this.day(p, date), changedDays };
  }

  /**
   * BR-20 to BR-24 on the server: validates every day, then applies all of them in one
   * transaction or none. Changes after closeAt are audited (BR-70); a changed day that has
   * locked rejects the whole save (BR-24).
   */
  private async save(
    p: RequestPrincipal,
    weekStart: string,
    days: ReadonlyArray<{ date: string; lines: SelectionLine[] }>,
  ): Promise<string[]> {
    const dates = days.map((d) => d.date);
    if (new Set(dates).size !== dates.length) {
      throw invalid('Each day may appear once', [{ path: 'days', message: 'duplicate' }]);
    }
    const me = await this.store.employee(this.prisma, p.factoryId, p.employeeId);
    if (!me.assignment.canRegister || !me.assignment.canteenId) {
      throw invalidState('Employee has no valid meal unit (BR-60)');
    }
    // Snapshot first if closeAt has passed, so the forecast never contains a later change.
    await this.forecast.closeDueWeeks(p.factoryId);
    const week = await this.store.loadWeek(this.prisma, me.assignment.canteenId, weekStart);
    if (!week?.published) throw invalidState('Menu is not published (BR-02)');
    this.validateDays(week, days);

    const lockTime = await this.settings.get(p.factoryId, 'day.lockTime');
    const changed: string[] = [];
    await this.prisma.$transaction(
      async (tx) => {
        const forecastTaken = await this.store.lockWeek(tx, week.id);
        const now = this.clock.now();
        const rows = await this.store.rowsOf(tx, [me.id], dates);
        for (const d of days) {
          const existing = rows.filter((r) => fromDb(r.date) === d.date);
          const before = activeQuantities(existing);
          const after = Object.fromEntries(
            d.lines.filter((l) => l.quantity > 0).map((l) => [l.menuItemId, l.quantity]),
          );
          if (sameQuantities(before, after)) continue;
          const window = employeeDayWindow({
            now,
            date: d.date,
            closeAt: week.closeAt!,
            lockTime,
            forecastTaken,
          });
          if (window === 'LOCKED' || isLeft(me, d.date)) throw dayLocked(d.date);
          await this.store.applyDay(tx, {
            employeeId: me.id,
            date: d.date,
            lines: d.lines,
            existing,
            source: 'SELF',
          });
          changed.push(d.date);
          if (window === 'AFTER_CLOSE') {
            // BR-23 / BR-70: every change after closeAt is recorded against the forecast.
            await this.audit.log(
              {
                actorId: me.id,
                action: 'registration.changedAfterClose',
                entityType: 'RegistrationDay',
                entityId: `${me.employeeCode}:${d.date}`,
                before: { quantities: before },
                after: { quantities: after, menuWeekId: week.id },
              },
              tx,
            );
          }
        }
      },
      { timeout: 30_000 },
    );
    return changed;
  }

  private validateDays(
    week: LoadedWeek,
    days: ReadonlyArray<{ date: string; lines: SelectionLine[] }>,
  ): void {
    const errors: Array<{ date: string; code: string; menuItemId?: string }> = [];
    for (const d of days) {
      const day = week.days.find((x) => x.date === d.date);
      if (!day?.isServing) {
        errors.push({ date: d.date, code: 'NOT_SERVING' });
        continue;
      }
      const res = validateDaySelection(d.lines, new Set(day.items.map((i) => i.id)));
      if (!res.ok) errors.push(...res.errors.map((e) => ({ date: d.date, ...e })));
    }
    if (errors.length > 0) throw invalid('Invalid selection (BR-20)', errors);
  }

  private async rowsWithNames(employeeId: string, dates: string[]) {
    const rows = await this.prisma.registration.findMany({
      where: { employeeId, date: { in: dates.map(toDb) }, session: SESSION },
      select: { ...REG_ROW_SELECT, menuItem: { select: { dish: { include: { type: true } } } } },
    });
    return rows.map((r) => ({
      ...r,
      displayName: mealDisplayName(r.menuItem.dish.type.name, r.menuItem.dish.name),
    }));
  }

  /** /history: past days with forecast, actual and status, newest first. */
  async history(p: RequestPrincipal, from?: string, to?: string): Promise<HistoryView> {
    const today = toVnDateTime(this.clock.now()).date;
    const end = to ?? today;
    const start = from ?? addDays(end, -27);
    if (start > end || addDays(start, MAX_HISTORY_DAYS) < end) {
      throw invalid('Invalid range', [{ path: 'from', message: `max ${MAX_HISTORY_DAYS} days` }]);
    }
    await this.forecast.cancelUnselectedAtLock(p.factoryId);
    const range = { gte: toDb(start), lte: toDb(end) };
    const [rows, lines] = await Promise.all([
      this.prisma.registration.findMany({
        where: { employeeId: p.employeeId, date: range },
        select: { ...REG_ROW_SELECT, menuItem: { select: { dish: { include: { type: true } } } } },
      }),
      this.prisma.forecastLine.findMany({
        where: { employeeId: p.employeeId, date: range },
        select: {
          date: true,
          quantity: true,
          menuItem: { select: { dish: { include: { type: true } } } },
        },
      }),
    ]);
    // Weeks with a forecast: a day of such a week without lines had a forecast of 0.
    const closedWeeks = await this.prisma.forecastLine.findMany({
      where: { employeeId: p.employeeId, date: range },
      distinct: ['menuWeekId'],
      select: { menuWeek: { select: { weekStart: true } } },
    });
    const forecastWeeks = new Set(closedWeeks.map((w) => fromDb(w.menuWeek.weekStart)));

    const byDate = new Map<string, HistoryDayView>();
    const entry = (date: string) => {
      let e = byDate.get(date);
      if (!e) {
        e = {
          date,
          forecast: forecastWeeks.has(mondayOf(date)) ? 0 : null,
          actual: 0,
          meals: [],
          status: 'NOT_REGISTERED',
        };
        byDate.set(date, e);
      }
      return e;
    };
    const meal = (e: HistoryDayView, name: string) => {
      let m = e.meals.find((x) => x.displayName === name);
      if (!m) {
        m = { displayName: name, forecast: 0, actual: 0, cancelled: 0 };
        e.meals.push(m);
      }
      return m;
    };
    for (const l of lines) {
      const e = entry(fromDb(l.date));
      e.forecast = (e.forecast ?? 0) + l.quantity;
      meal(e, mealDisplayName(l.menuItem.dish.type.name, l.menuItem.dish.name)).forecast +=
        l.quantity;
    }
    for (const r of rows) {
      if (r.quantity <= 0) continue;
      const e = entry(fromDb(r.date));
      const m = meal(e, mealDisplayName(r.menuItem.dish.type.name, r.menuItem.dish.name));
      if (r.status === 'ACTIVE') {
        e.actual += r.quantity;
        m.actual += r.quantity;
      } else {
        m.cancelled += r.quantity;
      }
    }
    const items = [...byDate.values()]
      .map((e) => ({
        ...e,
        status:
          e.actual > 0
            ? ('REGISTERED' as const)
            : e.meals.some((m) => m.cancelled > 0)
              ? ('CANCELLED' as const)
              : ('NOT_REGISTERED' as const),
      }))
      .sort((a, b) => b.date.localeCompare(a.date));
    return { from: start, to: end, items };
  }

  /**
   * BR-27: colleagues of a meal unit today: name, dish, quantity and eating / not eating only.
   * Never a reason for absence or leave. `unitId` defaults to the caller's meal unit.
   */
  async team(p: RequestPrincipal, unitId: string | null, q?: string): Promise<TeamDayView> {
    const id = unitId ?? p.mealUnitId;
    if (!id) throw notFound('No meal unit');
    const unit = await this.prisma.orgUnit.findUnique({ where: { id } });
    if (!unit || unit.factoryId !== p.factoryId) throw notFound('Unit not found');
    const date = toVnDateTime(this.clock.now()).date;
    const where: Prisma.EmployeeWhereInput[] = [
      { status: 'ACTIVE' },
      unit.isMealUnit ? { mealUnitId: unit.id } : { orgUnit: { path: { startsWith: unit.path } } },
    ];
    if (q) {
      where.push({
        OR: [
          { employeeCode: { contains: q, mode: 'insensitive' } },
          { name: { contains: q, mode: 'insensitive' } },
        ],
      });
    }
    const members = await this.prisma.employee.findMany({
      where: { AND: where },
      orderBy: [{ name: 'asc' }, { employeeCode: 'asc' }],
      select: {
        id: true,
        name: true,
        registrations: {
          where: { date: toDb(date), session: SESSION, status: 'ACTIVE', quantity: { gt: 0 } },
          select: { quantity: true, menuItem: { select: { dish: { include: { type: true } } } } },
        },
      },
      take: 500,
    });
    return {
      date,
      unitId: unit.id,
      unitName: unit.name,
      members: members.map((m) => ({
        key: m.id,
        name: m.name,
        meals: m.registrations.map((r) => ({
          displayName: mealDisplayName(r.menuItem.dish.type.name, r.menuItem.dish.name),
          quantity: r.quantity,
        })),
        eating: m.registrations.length > 0,
      })),
    };
  }
}

/** BR-36: an employee loses their meals from their end date on. */
export function isLeft(e: Pick<EmployeeForRegistration, 'endDate' | 'status'>, date: string) {
  return (e.endDate !== null && date >= fromDb(e.endDate)) || e.status === 'LEFT';
}
