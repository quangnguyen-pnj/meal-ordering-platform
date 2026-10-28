import { HttpStatus, Injectable } from '@nestjs/common';
import type { NotificationType, Prisma } from '@prisma/client';
import {
  addDays,
  checkMenuMix,
  computeCloseAt,
  dayLockAt,
  isDefaultServingDay,
  isoWeekdayOf,
  mealDisplayName,
  menuDayGaps,
  menuWeekDates,
  menuWeekGaps,
  resolveEmployeeMealAssignment,
  planMenuDayEdit,
  type MenuDayGap,
  type MenuDayView,
  type MenuMixRule,
  type MenuSaveResult,
  type MenuWeekSave,
  type MenuWeekView,
  type MyMenuWeekView,
} from '@meal/shared';
import { AuditService } from '../audit/audit.service';
import { ClockService } from '../clock/clock.service';
import { ApiException, invalid, invalidState, notFound } from '../common/api-error';
import { loadOrg } from '../org/org-index';
import { PrismaService } from '../prisma/prisma.service';
import { SettingsService } from '../settings/settings.service';

type Db = Prisma.TransactionClient | PrismaService;

const toDb = (date: string) => new Date(`${date}T00:00:00.000Z`);
const fromDb = (date: Date) => date.toISOString().slice(0, 10);

const WEEK_INCLUDE = {
  canteen: { select: { name: true } },
  days: {
    orderBy: { date: 'asc' },
    include: {
      items: {
        where: { removedAt: null },
        orderBy: { sortOrder: 'asc' },
        include: { dish: { include: { type: true } } },
      },
      desserts: {
        where: { removedAt: null },
        orderBy: { sortOrder: 'asc' },
        include: { dessert: true },
      },
    },
  },
} satisfies Prisma.MenuWeekInclude;

type StoredWeek = Prisma.MenuWeekGetPayload<{ include: typeof WEEK_INCLUDE }>;

/** 409 listing every serving day that lacks a main dish or a dessert (publishing rule). */
const incomplete = (days: Array<{ date: string; gaps: MenuDayGap[] }>) =>
  new ApiException(
    HttpStatus.CONFLICT,
    'MENU_INCOMPLETE',
    'Every serving day needs a main dish and a dessert',
    { details: days },
  );

function assertMonday(weekStart: string): void {
  let monday = false;
  try {
    monday = isoWeekdayOf(weekStart) === 1;
  } catch {
    monday = false;
  }
  if (!monday)
    throw invalid('weekStart must be a Monday', [{ path: 'weekStart', message: 'monday' }]);
}

/**
 * Weekly menus per canteen (BR-08, BR-11, BR-13, BR-15), publishing and closeAt (BR-03..BR-05),
 * and edits of a published menu (BR-14).
 */
@Injectable()
export class MenuService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly settings: SettingsService,
    private readonly audit: AuditService,
    private readonly clock: ClockService,
  ) {}

  async canteens(factoryId: string) {
    const rows = await this.prisma.canteen.findMany({
      where: { factoryId, active: true },
      orderBy: { code: 'asc' },
      select: { id: true, code: true, name: true },
    });
    return rows;
  }

  /** The HC admin's view of one canteen's week; a week never saved shows its default days. */
  async adminWeek(factoryId: string, canteenId: string, weekStart: string): Promise<MenuWeekView> {
    assertMonday(weekStart);
    const canteen = await this.prisma.canteen.findUnique({ where: { id: canteenId } });
    if (!canteen || canteen.factoryId !== factoryId) throw notFound('Canteen not found');
    const week = await this.prisma.menuWeek.findUnique({
      where: { canteenId_weekStart: { canteenId, weekStart: toDb(weekStart) } },
      include: WEEK_INCLUDE,
    });
    return this.view(factoryId, { canteenId, canteenName: canteen.name, weekStart }, week, true);
  }

  private async view(
    factoryId: string,
    ref: { canteenId: string; canteenName: string; weekStart: string },
    week: StoredWeek | null,
    withMix: boolean,
  ): Promise<MenuWeekView> {
    const now = this.clock.now();
    const [servingDays, closeRule, latePublishHours, lockTime, mixRules] = await Promise.all([
      this.settings.get(factoryId, 'servingDays'),
      this.settings.get(factoryId, 'registration.close'),
      this.settings.get(factoryId, 'registration.latePublishHours'),
      this.settings.get(factoryId, 'day.lockTime'),
      withMix ? this.mixRules(factoryId) : Promise.resolve([]),
    ]);
    const published = week !== null && week.status !== 'DRAFT';
    const stored = new Map((week?.days ?? []).map((d) => [fromDb(d.date), d]));
    const dates = [...new Set([...menuWeekDates(ref.weekStart), ...stored.keys()])].sort();

    const days: MenuDayView[] = dates.map((date) => {
      const d = stored.get(date);
      const isServing = d ? d.isServing : !published && isDefaultServingDay(date, servingDays);
      const items = (d?.items ?? []).map((i) => ({
        id: i.id,
        dishId: i.dishId,
        dishName: i.dish.name,
        type: { id: i.dish.type.id, name: i.dish.type.name, color: i.dish.type.color },
        displayName: mealDisplayName(i.dish.type.name, i.dish.name),
      }));
      return {
        date,
        isServing,
        items,
        desserts: (d?.desserts ?? []).map((x) => ({ id: x.dessertId, name: x.dessert.name })),
        mix:
          withMix && isServing
            ? checkMenuMix(
                items.map((i) => i.type.id),
                mixRules,
              )
            : null,
      };
    });

    const close =
      published && week.closeAt
        ? { closeAt: week.closeAt, latePublish: false, expected: false }
        : (() => {
            const r = computeCloseAt({
              weekStart: ref.weekStart,
              publishedAt: now,
              closeRule,
              latePublishHours,
            });
            return { closeAt: r.closeAt, latePublish: r.latePublish, expected: true };
          })();
    const latePublish =
      published && week.closeAt && week.publishedAt
        ? computeCloseAt({
            weekStart: ref.weekStart,
            publishedAt: week.publishedAt,
            closeRule,
            latePublishHours,
          }).latePublish
        : close.latePublish;

    return {
      id: week?.id ?? null,
      canteenId: ref.canteenId,
      canteenName: ref.canteenName,
      weekStart: ref.weekStart,
      status: week?.status ?? 'NEW',
      publishedAt: week?.publishedAt?.toISOString() ?? null,
      closeAt: close.closeAt.toISOString(),
      closeAtIsExpected: close.expected,
      latePublish,
      lockedDates: published
        ? dates.filter((d) => dayLockAt(d, lockTime).getTime() <= now.getTime())
        : [],
      days,
    };
  }

  /**
   * BR-11: saves a whole week. On a published week this is an edit under BR-14: a replaced dish
   * moves its registrations to the replacement and notifies the employees; a removed dish sets
   * its registrations to "Needs reselection" and notifies them.
   */
  async save(actorId: string, factoryId: string, input: MenuWeekSave): Promise<MenuSaveResult> {
    assertMonday(input.weekStart);
    const canteen = await this.prisma.canteen.findUnique({ where: { id: input.canteenId } });
    if (!canteen || canteen.factoryId !== factoryId) throw notFound('Canteen not found');
    if (!canteen.active) throw invalidState('Canteen is inactive');

    const weekEnd = addDays(input.weekStart, 6);
    const dates = input.days.map((d) => d.date);
    if (new Set(dates).size !== dates.length) {
      throw invalid('Each day may appear once', [{ path: 'days', message: 'duplicate' }]);
    }
    for (const day of input.days) {
      if (day.date < input.weekStart || day.date > weekEnd) {
        throw invalid('Day outside the week', [{ path: 'days', message: day.date }]);
      }
      if (!day.isServing && (day.items.length > 0 || day.dessertIds.length > 0)) {
        throw invalid('A non-serving day has no dishes', [{ path: 'days', message: day.date }]);
      }
    }

    const now = this.clock.now();
    const lockTime = await this.settings.get(factoryId, 'day.lockTime');
    const effects = { movedRegistrations: 0, flaggedRegistrations: 0, notifiedEmployees: 0 };

    await this.prisma.$transaction(
      async (tx) => {
        const week =
          (await tx.menuWeek.findUnique({
            where: {
              canteenId_weekStart: { canteenId: canteen.id, weekStart: toDb(input.weekStart) },
            },
            include: WEEK_INCLUDE,
          })) ??
          (await tx.menuWeek.create({
            data: { factoryId, canteenId: canteen.id, weekStart: toDb(input.weekStart) },
            include: WEEK_INCLUDE,
          }));
        const published = week.status !== 'DRAFT';
        const storedDays = new Map(week.days.map((d) => [fromDb(d.date), d]));
        const dishes = await this.loadDishes(tx, factoryId, input);
        const desserts = await this.loadDesserts(tx, factoryId, input);

        const before: Record<string, unknown> = {};
        const after: Record<string, unknown> = {};
        // A published day keeps at least one main dish and one dessert (publishing rule).
        const gaps: Array<{ date: string; gaps: MenuDayGap[] }> = [];
        const notices: Array<{ employeeIds: string[]; type: NotificationType; payload: object }> =
          [];

        for (const day of input.days) {
          const stored = storedDays.get(day.date);
          const currentItems = (stored?.items ?? []).map((i) => ({ id: i.id, dishId: i.dishId }));
          const currentDesserts = (stored?.desserts ?? []).map((d) => d.dessertId);
          const unchanged =
            stored !== undefined &&
            stored.isServing === day.isServing &&
            JSON.stringify(currentItems) ===
              JSON.stringify(day.items.map((i) => ({ id: i.id, dishId: i.dishId }))) &&
            JSON.stringify(currentDesserts) === JSON.stringify(day.dessertIds);
          if (unchanged) continue;

          if (published) {
            if (dayLockAt(day.date, lockTime).getTime() <= now.getTime()) {
              // TODO(BR-14): changing a day after its own lock is not covered by the plan
              // (open-questions Q15); refuse so locked days and later snapshots stay consistent.
              throw invalidState('Day is locked', { date: day.date });
            }
            if (stored?.isServing && !day.isServing) {
              // TODO(BR-08): turning a published serving day off would strand registrations
              // (open-questions Q16).
              throw invalidState('A published serving day cannot be turned off', {
                date: day.date,
              });
            }
            const dayGaps = menuDayGaps({
              isServing: day.isServing,
              mainCount: day.items.length,
              dessertCount: day.dessertIds.length,
            });
            if (dayGaps.length > 0) gaps.push({ date: day.date, gaps: dayGaps });
          }

          const plan = planMenuDayEdit(currentItems, day.items);
          if (!plan.ok) throw invalid('Invalid menu items', plan.errors);
          // New and replacement dishes must be in this canteen's library, not archived, and
          // served at lunch.
          for (const dishId of [
            ...plan.plan.add.map((a) => a.dishId),
            ...plan.plan.replace.map((r) => r.toDishId),
          ]) {
            const dish = dishes.get(dishId);
            if (
              !dish ||
              dish.archivedAt ||
              dish.canteenId !== canteen.id ||
              !dish.sessions.includes('LUNCH')
            ) {
              throw invalid('Dish not available', [{ path: 'dishId', message: dishId }]);
            }
          }
          for (const dessertId of day.dessertIds) {
            const dessert = desserts.get(dessertId);
            const isNew = !currentDesserts.includes(dessertId);
            if (!dessert || (isNew && (dessert.archivedAt || dessert.canteenId !== canteen.id))) {
              throw invalid('Dessert not available', [{ path: 'dessertId', message: dessertId }]);
            }
          }

          const menuDay =
            stored ??
            (await tx.menuDay.create({
              data: { menuWeekId: week.id, date: toDb(day.date), isServing: day.isServing },
              include: WEEK_INCLUDE.days.include,
            }));
          if (stored && stored.isServing !== day.isServing) {
            await tx.menuDay.update({
              where: { id: stored.id },
              data: { isServing: day.isServing },
            });
          }

          for (const k of plan.plan.keep) {
            await tx.menuItem.update({ where: { id: k.id }, data: { sortOrder: k.sortOrder } });
          }
          for (const a of plan.plan.add) {
            await tx.menuItem.create({
              data: { menuDayId: menuDay.id, dishId: a.dishId, sortOrder: a.sortOrder },
            });
          }
          for (const r of plan.plan.replace) {
            const replacement = await tx.menuItem.create({
              data: { menuDayId: menuDay.id, dishId: r.toDishId, sortOrder: r.sortOrder },
            });
            await tx.menuItem.update({
              where: { id: r.fromId },
              data: { removedAt: now, replacedByItemId: replacement.id },
            });
            // BR-14: registrations move to the replacement.
            const regs = await tx.registration.findMany({
              where: { menuItemId: r.fromId, status: { not: 'CANCELLED' } },
              select: { employeeId: true },
            });
            // The old item is kept on the row, so the employee can be told which dish changed.
            const moved = await tx.registration.updateMany({
              where: { menuItemId: r.fromId, status: { not: 'CANCELLED' } },
              data: { menuItemId: replacement.id, replacedFromItemId: r.fromId },
            });
            effects.movedRegistrations += moved.count;
            if (regs.length > 0) {
              notices.push({
                employeeIds: [...new Set(regs.map((x) => x.employeeId))],
                type: 'MENU_DISH_REPLACED',
                payload: {
                  date: day.date,
                  from: this.displayName(dishes, r.fromDishId),
                  to: this.displayName(dishes, r.toDishId),
                },
              });
            }
          }
          for (const r of plan.plan.remove) {
            await tx.menuItem.update({ where: { id: r.id }, data: { removedAt: now } });
            // BR-14: registrations need reselection; still unselected at the lock, they are
            // cancelled by ForecastService.cancelUnselectedAtLock (reselect-deadline job).
            const regs = await tx.registration.findMany({
              where: { menuItemId: r.id, status: 'ACTIVE' },
              select: { employeeId: true },
            });
            const flagged = await tx.registration.updateMany({
              where: { menuItemId: r.id, status: 'ACTIVE' },
              data: { status: 'NEEDS_RESELECT' },
            });
            effects.flaggedRegistrations += flagged.count;
            if (regs.length > 0) {
              notices.push({
                employeeIds: [...new Set(regs.map((x) => x.employeeId))],
                type: 'MENU_DISH_REMOVED',
                // The deadline comes from the day.lockTime setting, never a fixed hour (rule 3).
                payload: { date: day.date, dish: this.displayName(dishes, r.dishId), lockTime },
              });
            }
          }

          // Desserts are display-only (BR-11); removed rows are kept with removedAt.
          const keepDesserts = new Set(day.dessertIds);
          for (const row of stored?.desserts ?? []) {
            if (!keepDesserts.has(row.dessertId)) {
              await tx.menuDayDessert.update({ where: { id: row.id }, data: { removedAt: now } });
            }
          }
          for (const [sortOrder, dessertId] of day.dessertIds.entries()) {
            const row = stored?.desserts.find((x) => x.dessertId === dessertId);
            if (row) {
              if (row.sortOrder !== sortOrder) {
                await tx.menuDayDessert.update({ where: { id: row.id }, data: { sortOrder } });
              }
            } else {
              await tx.menuDayDessert.create({
                data: { menuDayId: menuDay.id, dessertId, sortOrder },
              });
            }
          }

          before[day.date] = stored
            ? {
                isServing: stored.isServing,
                dishIds: currentItems.map((i) => i.dishId),
                dessertIds: currentDesserts,
              }
            : null;
          after[day.date] = {
            isServing: day.isServing,
            dishIds: day.items.map((i) => i.dishId),
            dessertIds: day.dessertIds,
          };
        }

        if (gaps.length > 0) throw incomplete(gaps);

        const recipients = new Set<string>();
        for (const n of notices) {
          await tx.notification.createMany({
            data: n.employeeIds.map((employeeId) => ({
              employeeId,
              type: n.type,
              payload: { ...n.payload, weekStart: input.weekStart, canteen: canteen.name },
              createdAt: now,
            })),
          });
          n.employeeIds.forEach((id) => recipients.add(id));
        }
        effects.notifiedEmployees = recipients.size;

        if (Object.keys(after).length > 0) {
          await this.audit.log(
            {
              actorId,
              action: published ? 'menu.edited' : 'menu.saved',
              entityType: 'MenuWeek',
              entityId: week.id,
              before: before as Prisma.InputJsonValue,
              after: { days: after, effects } as Prisma.InputJsonValue,
              reason: input.reason,
            },
            tx,
          );
        }
      },
      { timeout: 30_000 },
    );

    return {
      menu: await this.adminWeek(factoryId, canteen.id, input.weekStart),
      effects,
    };
  }

  /** BR-02 / BR-03 / BR-04: publishing opens registration and fixes closeAt. Audited. */
  async publish(actorId: string, factoryId: string, weekId: string): Promise<MenuWeekView> {
    const week = await this.prisma.menuWeek.findUnique({
      where: { id: weekId },
      include: {
        canteen: true,
        days: {
          include: {
            items: { where: { removedAt: null }, select: { id: true } },
            desserts: { where: { removedAt: null }, select: { id: true } },
          },
        },
      },
    });
    if (!week || week.factoryId !== factoryId) throw notFound('Menu week not found');
    if (week.status !== 'DRAFT') throw invalidState('Menu week is already published');
    const gaps = menuWeekGaps(
      week.days.map((d) => ({
        date: fromDb(d.date),
        isServing: d.isServing,
        mainCount: d.items.length,
        dessertCount: d.desserts.length,
      })),
    );
    if (gaps.length > 0) throw incomplete(gaps);

    const weekStart = fromDb(week.weekStart);
    const now = this.clock.now();
    const result = computeCloseAt({
      weekStart,
      publishedAt: now,
      closeRule: await this.settings.get(factoryId, 'registration.close'),
      latePublishHours: await this.settings.get(factoryId, 'registration.latePublishHours'),
    });
    await this.prisma.$transaction(async (tx) => {
      // Conditional update: two concurrent publishes cannot both succeed.
      const updated = await tx.menuWeek.updateMany({
        where: { id: weekId, status: 'DRAFT' },
        data: {
          status: 'PUBLISHED',
          publishedAt: now,
          publishedById: actorId,
          closeAt: result.closeAt,
        },
      });
      if (updated.count === 0) throw invalidState('Menu week is already published');
      await this.audit.log(
        {
          actorId,
          action: 'menu.published',
          entityType: 'MenuWeek',
          entityId: weekId,
          after: {
            canteenId: week.canteenId,
            weekStart,
            closeAt: result.closeAt.toISOString(),
            latePublish: result.latePublish,
          },
        },
        tx,
      );
    });
    return this.adminWeek(factoryId, week.canteenId, weekStart);
  }

  /**
   * Publishes straight from the builder, without a separate "save draft" step (product owner,
   * 2026-10-23): the days are saved, then the week is published. Every serving day must have a
   * main dish and a dessert; that is checked on the submitted days before anything is written,
   * and again by `publish` on the stored week.
   */
  async saveAndPublish(
    actorId: string,
    factoryId: string,
    input: MenuWeekSave,
  ): Promise<MenuWeekView> {
    const gaps = menuWeekGaps(
      input.days.map((d) => ({
        date: d.date,
        isServing: d.isServing,
        mainCount: d.items.length,
        dessertCount: d.dessertIds.length,
      })),
    );
    if (gaps.length > 0) throw incomplete(gaps);
    const saved = await this.save(actorId, factoryId, input);
    if (saved.menu.status !== 'DRAFT' || saved.menu.id === null) {
      throw invalidState('Menu week is already published');
    }
    return this.publish(actorId, factoryId, saved.menu.id);
  }

  /**
   * BR-02 / BR-15 / BR-63: an employee sees the published menu of their meal unit's canteen and
   * never a draft. Employees on the "unassigned" list see no menu.
   */
  async myWeek(employeeId: string, factoryId: string, weekStart: string): Promise<MyMenuWeekView> {
    assertMonday(weekStart);
    const employee = await this.prisma.employee.findUniqueOrThrow({
      where: { id: employeeId },
      select: { orgUnitId: true, floorOverrideId: true },
    });
    const org = await loadOrg(this.prisma, factoryId);
    const assignment = resolveEmployeeMealAssignment({
      orgUnitId: employee.orgUnitId,
      floorOverrideId: employee.floorOverrideId,
      index: org.index,
    });
    if (!assignment.canRegister || !assignment.canteenId) return { state: 'UNASSIGNED', weekStart };

    const week = await this.prisma.menuWeek.findUnique({
      where: {
        canteenId_weekStart: { canteenId: assignment.canteenId, weekStart: toDb(weekStart) },
      },
      include: WEEK_INCLUDE,
    });
    const canteen = await this.prisma.canteen.findUniqueOrThrow({
      where: { id: assignment.canteenId },
    });
    if (!week || week.status === 'DRAFT') {
      return { state: 'NOT_PUBLISHED', weekStart, canteenName: canteen.name };
    }
    const menu = await this.view(
      factoryId,
      { canteenId: canteen.id, canteenName: canteen.name, weekStart },
      week,
      false,
    );
    return { state: 'PUBLISHED', menu: { ...menu, days: menu.days.filter((d) => d.isServing) } };
  }

  private displayName(
    dishes: Map<string, { name: string; type: { name: string } }>,
    dishId: string,
  ): string {
    const dish = dishes.get(dishId);
    return dish ? mealDisplayName(dish.type.name, dish.name) : dishId;
  }

  /** BR-13: each dish type's usual bounds per serving day (inactive types are not checked). */
  private async mixRules(factoryId: string): Promise<MenuMixRule[]> {
    const types = await this.prisma.dishType.findMany({
      where: { factoryId, active: true },
      orderBy: [{ sortOrder: 'asc' }, { name: 'asc' }],
    });
    return types.map((t) => ({
      typeId: t.id,
      typeName: t.name,
      minPerDay: t.minPerDay,
      maxPerDay: t.maxPerDay,
    }));
  }

  private async loadDishes(db: Db, factoryId: string, input: MenuWeekSave) {
    const ids = new Set(input.days.flatMap((d) => d.items.map((i) => i.dishId)));
    // Also load dishes of current items, for BR-14 notice names.
    const current = await db.menuItem.findMany({
      where: {
        removedAt: null,
        menuDay: {
          menuWeek: { canteenId: input.canteenId, weekStart: toDb(input.weekStart) },
        },
      },
      select: { dishId: true },
    });
    current.forEach((c) => ids.add(c.dishId));
    const rows = await db.dish.findMany({
      where: { factoryId, id: { in: [...ids] } },
      include: { type: true },
    });
    return new Map(rows.map((r) => [r.id, r]));
  }

  private async loadDesserts(db: Db, factoryId: string, input: MenuWeekSave) {
    const ids = [...new Set(input.days.flatMap((d) => d.dessertIds))];
    const rows = await db.dessert.findMany({ where: { factoryId, id: { in: ids } } });
    return new Map(rows.map((r) => [r.id, r]));
  }
}
