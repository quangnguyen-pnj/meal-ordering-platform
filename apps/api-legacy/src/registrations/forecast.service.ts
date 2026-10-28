import { Injectable, Logger } from '@nestjs/common';
import type { Prisma } from '@prisma/client';
import {
  dayLockAt,
  forecastLinesOf,
  isLostByLeaver,
  resolveEmployeeMealAssignment,
  toVnDateTime,
} from '@meal/shared';
import { AuditService } from '../audit/audit.service';
import { ClockService } from '../clock/clock.service';
import { costCenterIdsByCode, loadOrg } from '../org/org-index';
import { PrismaService } from '../prisma/prisma.service';
import { SettingsService } from '../settings/settings.service';
import { fromDb, toDb } from './registration-store';

type Tx = Prisma.TransactionClient;

/**
 * Checkpoints of registration that happen at an instant rather than on a request:
 * - BR-07: the forecast snapshot at each week's closeAt (close-week job).
 * - BR-14: registrations still waiting for reselection are cancelled at the day's lock.
 * - BR-36: leavers lose their meals from their end date (called by the org sync).
 *
 * Every method is idempotent. Jobs call them on a schedule, and every registration request calls
 * `catchUp` first, so a missed job never lets a rule slip (plan 6.5: self-healing).
 */
@Injectable()
export class ForecastService {
  private readonly logger = new Logger(ForecastService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly clock: ClockService,
    private readonly settings: SettingsService,
    private readonly audit: AuditService,
  ) {}

  /** Everything that is due for the factory: snapshots, then lock-time cancellations. */
  async catchUp(factoryId: string): Promise<void> {
    await this.closeDueWeeks(factoryId);
    await this.cancelUnselectedAtLock(factoryId);
  }

  /** BR-07: takes the forecast snapshot of every published week whose closeAt has passed. */
  async closeDueWeeks(factoryId?: string): Promise<number> {
    const due = await this.prisma.menuWeek.findMany({
      where: {
        ...(factoryId ? { factoryId } : {}),
        status: 'PUBLISHED',
        closedAt: null,
        closeAt: { lte: this.clock.now() },
      },
      select: { id: true },
    });
    let closed = 0;
    for (const week of due) if (await this.ensureClosed(week.id)) closed += 1;
    return closed;
  }

  /**
   * BR-07 / BR-64: writes the immutable ForecastLine snapshot of one week with the mapping in
   * effect now, and marks the week CLOSED. The conditional update takes the week's row lock, so
   * it waits for registration writes holding FOR SHARE (see RegistrationStore.lockWeek) and two
   * runs can never both write lines. Returns true when this call took the snapshot.
   */
  async ensureClosed(menuWeekId: string): Promise<boolean> {
    const now = this.clock.now();
    return this.prisma.$transaction(
      async (tx) => {
        const taken = await tx.menuWeek.updateMany({
          where: { id: menuWeekId, status: 'PUBLISHED', closedAt: null, closeAt: { lte: now } },
          data: { status: 'CLOSED', closedAt: now },
        });
        if (taken.count === 0) return false;
        const week = await tx.menuWeek.findUniqueOrThrow({ where: { id: menuWeekId } });

        const registrations = await tx.registration.findMany({
          where: { menuItem: { menuDay: { menuWeekId } } },
          select: {
            employeeId: true,
            menuItemId: true,
            date: true,
            session: true,
            quantity: true,
            status: true,
          },
        });
        const lines = forecastLinesOf(registrations);
        const employees = await tx.employee.findMany({
          where: { id: { in: [...new Set(lines.map((l) => l.employeeId))] } },
          select: {
            id: true,
            orgUnitId: true,
            floorOverrideId: true,
            dmpCostCenterCode: true,
          },
        });
        const [org, ccByCode] = await Promise.all([
          loadOrg(tx, week.factoryId),
          costCenterIdsByCode(tx, week.factoryId),
        ]);
        const mapping = new Map(
          employees.map((e) => {
            const a = resolveEmployeeMealAssignment({
              orgUnitId: e.orgUnitId,
              floorOverrideId: e.floorOverrideId,
              dmpCostCenterId: e.dmpCostCenterCode
                ? (ccByCode.get(e.dmpCostCenterCode) ?? null)
                : null,
              index: org.index,
            });
            return [
              e.id,
              {
                mealUnitId: a.mealUnitId,
                floorId: a.floorId,
                // The menu's canteen is the one the meals were taken against.
                canteenId: week.canteenId,
                costCenterId: a.costCenterId,
              },
            ] as const;
          }),
        );
        if (lines.length > 0) {
          await tx.forecastLine.createMany({
            data: lines.map((l) => ({
              menuWeekId,
              employeeId: l.employeeId,
              date: l.date,
              session: l.session,
              menuItemId: l.menuItemId,
              quantity: l.quantity,
              ...mapping.get(l.employeeId)!,
            })),
          });
        }
        const meals = lines.reduce((sum, l) => sum + l.quantity, 0);
        await this.audit.log(
          {
            actorId: null,
            action: 'forecast.snapshot',
            entityType: 'MenuWeek',
            entityId: menuWeekId,
            after: {
              weekStart: fromDb(week.weekStart),
              canteenId: week.canteenId,
              closeAt: week.closeAt?.toISOString() ?? null,
              lines: lines.length,
              meals,
            },
          },
          tx,
        );
        this.logger.log(`Forecast snapshot of week ${menuWeekId}: ${lines.length} lines`);
        return true;
      },
      { timeout: 60_000 },
    );
  }

  /**
   * BR-14: a registration on a removed dish that is still waiting for reselection at the day's
   * lock is cancelled. Automatic, so audited per registration with no actor (BR-70, Q18).
   */
  async cancelUnselectedAtLock(factoryId?: string): Promise<number> {
    const now = this.clock.now();
    const today = toVnDateTime(now).date;
    const factories = factoryId
      ? [factoryId]
      : (await this.prisma.factory.findMany({ select: { id: true } })).map((f) => f.id);
    let cancelled = 0;
    for (const fid of factories) {
      const lockTime = await this.settings.get(fid, 'day.lockTime');
      const locked = now.getTime() >= dayLockAt(today, lockTime).getTime();
      const rows = await this.prisma.registration.findMany({
        where: {
          status: 'NEEDS_RESELECT',
          date: locked ? { lte: toDb(today) } : { lt: toDb(today) },
          employee: { orgUnit: { factoryId: fid } },
        },
        select: { id: true, employeeId: true, menuItemId: true, date: true, quantity: true },
      });
      if (rows.length === 0) continue;
      await this.prisma.$transaction(async (tx) => {
        for (const r of rows) {
          const res = await tx.registration.updateMany({
            where: { id: r.id, status: 'NEEDS_RESELECT' },
            data: { status: 'CANCELLED' },
          });
          if (res.count === 0) continue;
          cancelled += 1;
          await this.audit.log(
            {
              actorId: null,
              action: 'registration.cancelledUnselected',
              entityType: 'Registration',
              entityId: r.id,
              before: { status: 'NEEDS_RESELECT', quantity: r.quantity },
              after: { status: 'CANCELLED', date: fromDb(r.date), menuItemId: r.menuItemId },
            },
            tx,
          );
        }
      });
    }
    return cancelled;
  }
}

/**
 * BR-36: employees who leave lose all meals from their end date. Runs inside the org sync's
 * transaction; days whose lock has passed are left to reconciliation (`isLostByLeaver`). Each
 * cancellation is audited with no actor (BR-70).
 */
export async function cancelLeaversMeals(
  tx: Tx,
  audit: AuditService,
  args: { factoryId: string; now: Date; lockTime: string },
): Promise<number> {
  const today = toVnDateTime(args.now).date;
  const rows = await tx.registration.findMany({
    where: {
      status: { in: ['ACTIVE', 'NEEDS_RESELECT'] },
      date: { gte: toDb(today) },
      employee: { endDate: { not: null }, orgUnit: { factoryId: args.factoryId } },
    },
    select: {
      id: true,
      date: true,
      quantity: true,
      status: true,
      menuItemId: true,
      employee: { select: { endDate: true } },
    },
  });
  let cancelled = 0;
  for (const r of rows) {
    const lost = isLostByLeaver({
      date: fromDb(r.date),
      endDate: fromDb(r.employee.endDate!),
      now: args.now,
      lockTime: args.lockTime,
    });
    if (!lost) continue;
    await tx.registration.update({ where: { id: r.id }, data: { status: 'CANCELLED' } });
    await audit.log(
      {
        actorId: null,
        action: 'registration.cancelledLeaver',
        entityType: 'Registration',
        entityId: r.id,
        before: { status: r.status, quantity: r.quantity },
        after: {
          status: 'CANCELLED',
          date: fromDb(r.date),
          menuItemId: r.menuItemId,
          endDate: fromDb(r.employee.endDate!),
        },
      },
      tx,
    );
    cancelled += 1;
  }
  return cancelled;
}
