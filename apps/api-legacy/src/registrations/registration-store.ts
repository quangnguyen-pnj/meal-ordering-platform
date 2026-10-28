import { Injectable } from '@nestjs/common';
import type { Prisma, RegistrationSource, RegistrationStatus } from '@prisma/client';
import {
  compareWithForecast,
  dayLockAt,
  employeeDayWindow,
  mealDisplayName,
  mondayOf,
  resolveEmployeeMealAssignment,
  type DishTypeLabel,
  type EmployeeMealAssignment,
  type MyDayView,
  type SelectionLine,
} from '@meal/shared';
import { costCenterIdsByCode, loadOrg, type OrgSnapshot } from '../org/org-index';
import { PrismaService } from '../prisma/prisma.service';

type Db = Prisma.TransactionClient | PrismaService;

export const toDb = (date: string) => new Date(`${date}T00:00:00.000Z`);
export const fromDb = (date: Date) => date.toISOString().slice(0, 10);

/** The MVP serves lunch only (plan section 1). */
export const SESSION = 'LUNCH' as const;

const WEEK_INCLUDE = {
  canteen: { select: { name: true } },
  days: {
    orderBy: { date: 'asc' },
    include: {
      items: {
        where: { removedAt: null, session: SESSION },
        orderBy: { sortOrder: 'asc' },
        include: {
          dish: { select: { name: true, type: true } },
          // BR-14: the items this one replaced, for the red "dish changed" note.
          replaces: {
            select: {
              id: true,
              removedAt: true,
              dish: { select: { name: true, type: { select: { name: true } } } },
            },
          },
        },
      },
      desserts: {
        where: { removedAt: null },
        orderBy: { sortOrder: 'asc' },
        include: { dessert: { select: { name: true } } },
      },
    },
  },
} satisfies Prisma.MenuWeekInclude;

export interface LoadedItem {
  id: string;
  displayName: string;
  type: DishTypeLabel;
  /** BR-14: items whose dish this one replaced, with when they were replaced. */
  replaced: Array<{ id: string; displayName: string; removedAt: Date | null }>;
}

export interface LoadedDay {
  date: string;
  isServing: boolean;
  items: LoadedItem[];
  desserts: string[];
}

export interface LoadedWeek {
  id: string;
  factoryId: string;
  canteenId: string;
  canteenName: string;
  weekStart: string;
  /** BR-02: employees never see or register on a draft. */
  published: boolean;
  closeAt: Date | null;
  /** BR-07: set when the forecast snapshot was taken. */
  closedAt: Date | null;
  days: LoadedDay[];
}

/** A stored registration row as the write path needs it. */
export interface RegRow {
  id: string;
  employeeId: string;
  menuItemId: string;
  date: Date;
  quantity: number;
  status: RegistrationStatus;
  isExtra: boolean;
  /** BR-14: set when the HC admin replaced the dish and this meal moved with it. */
  replacedFromItemId: string | null;
}

export const REG_ROW_SELECT = {
  id: true,
  employeeId: true,
  menuItemId: true,
  date: true,
  quantity: true,
  status: true,
  isExtra: true,
  replacedFromItemId: true,
} satisfies Prisma.RegistrationSelect;

/** Quantities by menu item; only positive active quantities count as meals (BR-20). */
export type Quantities = Record<string, number>;

export interface EmployeeForRegistration {
  id: string;
  employeeCode: string;
  name: string;
  status: 'ACTIVE' | 'LEFT';
  orgUnitId: string;
  endDate: Date | null;
  assignment: EmployeeMealAssignment;
}

/**
 * Shared internals of the registration flows: loading a canteen's week, resolving who may
 * register where (BR-60, BR-63), the week lock that orders writes against the forecast
 * snapshot (BR-07), and applying a day's selection (BR-20).
 */
@Injectable()
export class RegistrationStore {
  constructor(private readonly prisma: PrismaService) {}

  async loadWeek(db: Db, canteenId: string, weekStart: string): Promise<LoadedWeek | null> {
    const week = await db.menuWeek.findUnique({
      where: { canteenId_weekStart: { canteenId, weekStart: toDb(weekStart) } },
      include: WEEK_INCLUDE,
    });
    if (!week) return null;
    return {
      id: week.id,
      factoryId: week.factoryId,
      canteenId: week.canteenId,
      canteenName: week.canteen.name,
      weekStart,
      published: week.status !== 'DRAFT' && week.closeAt !== null,
      closeAt: week.closeAt,
      closedAt: week.closedAt,
      days: week.days.map((d) => ({
        date: fromDb(d.date),
        isServing: d.isServing,
        items: d.items.map((i) => ({
          id: i.id,
          displayName: mealDisplayName(i.dish.type.name, i.dish.name),
          type: { id: i.dish.type.id, name: i.dish.type.name, color: i.dish.type.color },
          replaced: i.replaces.map((r) => ({
            id: r.id,
            displayName: mealDisplayName(r.dish.type.name, r.dish.name),
            removedAt: r.removedAt,
          })),
        })),
        desserts: d.desserts.map((x) => x.dessert.name),
      })),
    };
  }

  /** The published week of `canteenId` that contains `date`, with that day, or null. */
  async publishedDay(
    db: Db,
    canteenId: string,
    date: string,
  ): Promise<{ week: LoadedWeek; day: LoadedDay } | null> {
    const week = await this.loadWeek(db, canteenId, mondayOf(date));
    const day = week?.days.find((d) => d.date === date);
    if (!week?.published || !day?.isServing) return null;
    return { week, day };
  }

  /** BR-60 / BR-63 for a set of employees, with one org load. */
  async employees(
    db: Db,
    factoryId: string,
    where: Prisma.EmployeeWhereInput,
    org?: OrgSnapshot,
  ): Promise<EmployeeForRegistration[]> {
    const [tree, ccByCode, rows] = await Promise.all([
      org ?? loadOrg(db, factoryId),
      costCenterIdsByCode(db, factoryId),
      db.employee.findMany({
        where: { AND: [where, { orgUnit: { factoryId } }] },
        orderBy: [{ name: 'asc' }, { employeeCode: 'asc' }],
        select: {
          id: true,
          employeeCode: true,
          name: true,
          status: true,
          orgUnitId: true,
          endDate: true,
          floorOverrideId: true,
          dmpCostCenterCode: true,
        },
      }),
    ]);
    return rows.map((e) => ({
      id: e.id,
      employeeCode: e.employeeCode,
      name: e.name,
      status: e.status,
      orgUnitId: e.orgUnitId,
      endDate: e.endDate,
      assignment: resolveEmployeeMealAssignment({
        orgUnitId: e.orgUnitId,
        floorOverrideId: e.floorOverrideId,
        dmpCostCenterId: e.dmpCostCenterCode ? (ccByCode.get(e.dmpCostCenterCode) ?? null) : null,
        index: tree.index,
      }),
    }));
  }

  async employee(db: Db, factoryId: string, employeeId: string): Promise<EmployeeForRegistration> {
    const [e] = await this.employees(db, factoryId, { id: employeeId });
    if (!e) throw new Error(`Employee ${employeeId} not found`);
    return e;
  }

  /**
   * Takes a shared lock on the week row for the rest of the transaction, and reports whether the
   * forecast was already taken. The snapshot takes the row's exclusive lock, so a write either
   * lands before the snapshot (and is in the forecast) or after it (and is a difference, BR-23).
   */
  async lockWeek(tx: Prisma.TransactionClient, menuWeekId: string): Promise<boolean> {
    const rows = await tx.$queryRaw<Array<{ closedAt: Date | null }>>`
      SELECT "closedAt" FROM "MenuWeek" WHERE "id" = ${menuWeekId} FOR SHARE`;
    return rows[0]?.closedAt != null;
  }

  async rowsOf(db: Db, employeeIds: string[], dates: string[]): Promise<RegRow[]> {
    if (employeeIds.length === 0 || dates.length === 0) return [];
    return db.registration.findMany({
      where: { employeeId: { in: employeeIds }, date: { in: dates.map(toDb) }, session: SESSION },
      select: REG_ROW_SELECT,
    });
  }

  /**
   * BR-20: makes the employee's active lines of `date` equal `lines` (items not listed become 0).
   * Rows are never deleted (rule 7): a removed meal keeps its row with quantity 0. Registrations
   * waiting for reselection (BR-14) are resolved, i.e. cancelled, once the day is saved with a
   * new selection. Returns the before and after quantities, or `changed: false`.
   */
  async applyDay(
    tx: Prisma.TransactionClient,
    args: {
      employeeId: string;
      date: string;
      lines: readonly SelectionLine[];
      existing: readonly RegRow[];
      source: RegistrationSource;
    },
  ): Promise<{ changed: boolean; before: Quantities; after: Quantities }> {
    const before = activeQuantities(args.existing);
    const after: Quantities = {};
    for (const l of args.lines) if (l.quantity > 0) after[l.menuItemId] = l.quantity;
    if (sameQuantities(before, after)) return { changed: false, before, after };

    const byItem = new Map(args.existing.map((r) => [r.menuItemId, r]));
    for (const itemId of new Set([...Object.keys(before), ...Object.keys(after)])) {
      const want = after[itemId] ?? 0;
      const row = byItem.get(itemId);
      if (!row) {
        await tx.registration.create({
          data: {
            employeeId: args.employeeId,
            menuItemId: itemId,
            date: toDb(args.date),
            session: SESSION,
            quantity: want,
            source: args.source,
          },
        });
      } else if (row.quantity !== want || (want > 0 && row.status !== 'ACTIVE')) {
        await tx.registration.update({
          where: { id: row.id },
          data: { quantity: want, status: 'ACTIVE', source: args.source },
        });
      }
    }
    const pending = args.existing.filter((r) => r.status === 'NEEDS_RESELECT').map((r) => r.id);
    if (pending.length > 0) {
      await tx.registration.updateMany({
        where: { id: { in: pending } },
        data: { status: 'CANCELLED' },
      });
    }
    return { changed: true, before, after };
  }

  /**
   * BR-25: adds `quantity` meals flagged "Extra" with the reason. The registration row of the
   * item is reused (one row per employee and item); the audit log keeps every addition.
   */
  async addExtra(
    tx: Prisma.TransactionClient,
    args: {
      employeeId: string;
      date: string;
      menuItemId: string;
      quantity: number;
      reason: string;
      existing: RegRow | undefined;
    },
  ): Promise<{ before: number; after: number }> {
    const row = args.existing;
    const before = row && row.status === 'ACTIVE' ? row.quantity : 0;
    const after = before + args.quantity;
    if (row) {
      await tx.registration.update({
        where: { id: row.id },
        data: {
          quantity: after,
          status: 'ACTIVE',
          isExtra: true,
          extraReason: args.reason,
          source: 'ADMIN',
        },
      });
    } else {
      await tx.registration.create({
        data: {
          employeeId: args.employeeId,
          menuItemId: args.menuItemId,
          date: toDb(args.date),
          session: SESSION,
          quantity: after,
          source: 'ADMIN',
          isExtra: true,
          extraReason: args.reason,
        },
      });
    }
    return { before, after };
  }

  /** Forecast lines of employees in a week, by employee, then date, then item. */
  async forecastOf(
    db: Db,
    menuWeekId: string,
    employeeIds: string[],
  ): Promise<Map<string, Map<string, Quantities>>> {
    const lines = await db.forecastLine.findMany({
      where: { menuWeekId, employeeId: { in: employeeIds } },
      select: { employeeId: true, date: true, menuItemId: true, quantity: true },
    });
    const out = new Map<string, Map<string, Quantities>>();
    for (const l of lines) {
      const byDate = out.get(l.employeeId) ?? new Map<string, Quantities>();
      const q = byDate.get(fromDb(l.date)) ?? {};
      q[l.menuItemId] = (q[l.menuItemId] ?? 0) + l.quantity;
      byDate.set(fromDb(l.date), q);
      out.set(l.employeeId, byDate);
    }
    return out;
  }

  /**
   * The employee's view of one serving day (BR-22 to BR-24): current quantities, the forecast
   * once taken, meals waiting for reselection and the difference from the forecast.
   */
  dayView(args: {
    week: LoadedWeek;
    day: LoadedDay;
    rows: readonly (RegRow & { displayName?: string })[];
    forecast: Quantities | null;
    now: Date;
    lockTime: string;
    endDate: Date | null;
  }): MyDayView {
    const { week, day } = args;
    const current = activeQuantities(args.rows);
    const forecast = args.forecast;
    const comparison = compareWithForecast(
      Object.entries(forecast ?? {}).map(([menuItemId, quantity]) => ({ menuItemId, quantity })),
      Object.entries(current).map(([menuItemId, quantity]) => ({ menuItemId, quantity })),
    );
    const leaver = args.endDate !== null && day.date >= fromDb(args.endDate);
    return {
      date: day.date,
      window: leaver
        ? 'LOCKED'
        : employeeDayWindow({
            now: args.now,
            date: day.date,
            closeAt: week.closeAt!,
            lockTime: args.lockTime,
            forecastTaken: week.closedAt !== null,
          }),
      lockAt: dayLockAt(day.date, args.lockTime).toISOString(),
      items: day.items.map((i) => ({
        menuItemId: i.id,
        displayName: i.displayName,
        type: i.type,
        quantity: current[i.id] ?? 0,
        forecast: forecast?.[i.id] ?? 0,
        replacedAfterClose: replacedAfterClose(week, i, args.rows),
      })),
      needsReselect: args.rows
        .filter((r) => r.status === 'NEEDS_RESELECT' && r.quantity > 0)
        .map((r) => ({ displayName: r.displayName ?? '', quantity: r.quantity })),
      desserts: day.desserts,
      actualTotal: comparison.actualTotal,
      forecastTotal: forecast ? comparison.forecastTotal : null,
      changed: forecast ? comparison.changed : false,
      extra: args.rows.some((r) => r.isExtra && r.status === 'ACTIVE' && r.quantity > 0),
    };
  }
}

/**
 * BR-14 (product owner, 2026-10-23): when the HC admin replaces a dish after closeAt, an employee
 * whose meal moved to the new dish sees a red note naming the dish they had chosen. Only that
 * employee: someone who picks the new dish themselves gets no note.
 */
export function replacedAfterClose(
  week: Pick<LoadedWeek, 'closeAt'>,
  item: LoadedItem,
  rows: readonly RegRow[],
): { from: string } | null {
  if (!week.closeAt) return null;
  const closeAt = week.closeAt.getTime();
  for (const r of rows) {
    if (r.menuItemId !== item.id || r.status !== 'ACTIVE' || r.quantity <= 0) continue;
    const from = item.replaced.find((x) => x.id === r.replacedFromItemId);
    if (from?.removedAt && from.removedAt.getTime() >= closeAt) return { from: from.displayName };
  }
  return null;
}

/** Positive quantities of ACTIVE rows by menu item. */
export function activeQuantities(rows: readonly RegRow[]): Quantities {
  const out: Quantities = {};
  for (const r of rows) {
    if (r.status === 'ACTIVE' && r.quantity > 0) {
      out[r.menuItemId] = (out[r.menuItemId] ?? 0) + r.quantity;
    }
  }
  return out;
}

export function sameQuantities(a: Quantities, b: Quantities): boolean {
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
  for (const k of keys) if ((a[k] ?? 0) !== (b[k] ?? 0)) return false;
  return true;
}

export const sumQuantities = (q: Quantities) => Object.values(q).reduce((s, n) => s + n, 0);
