import { HttpStatus, Inject, Injectable, Logger } from '@nestjs/common';
import type { Prisma } from '@prisma/client';
import { randomUUID } from 'node:crypto';
import { buildDmpTree, toVnDateTime } from '@meal/shared';
import { AuditService } from '../audit/audit.service';
import { ClockService } from '../clock/clock.service';
import { ApiException, invalidState } from '../common/api-error';
import { DMP_CLIENT, type DmpClient, type DmpEmployee, type DmpOrgNode } from '../dmp/dmp.client';
import { PrismaService } from '../prisma/prisma.service';
import { SystemAdminBootstrap } from '../rbac/system-admin-bootstrap.service';
import { cancelLeaversMeals } from '../registrations/forecast.service';
import { SettingsService } from '../settings/settings.service';
import { recomputeMealUnits } from './org-index';

/** Postgres advisory-lock key, so two org syncs never write at the same time. */
const SYNC_LOCK_KEY = 4_100_001;

export interface OrgSyncResult {
  syncRunId: string;
  status: 'SUCCESS' | 'FAILED';
  details: OrgSyncDetails | null;
  error: string | null;
}

export interface OrgSyncDetails {
  orgUnits: { created: number; updated: number; deactivated: number; total: number };
  employees: { created: number; updated: number; left: number; total: number };
  costCentersCreated: number;
  mealUnitsRecomputed: number;
  /** DMP employees whose org node is unknown; they were not written. */
  skippedEmployees: Array<{ employeeCode: string; orgUnitCode: string }>;
  /** Employees in the database that DMP no longer returns (left unchanged, open question Q13). */
  missingFromDmp: number;
  /** BR-36: registrations cancelled because their day is on or after the employee's end date. */
  mealsCancelledForLeavers: number;
}

/**
 * Plan P1 / section 5: syncs the org tree and employees from DMP (daily job and manual trigger).
 * Every run writes a SyncRun. Data is written only when the whole run succeeds: DMP is read and
 * validated first, then everything is written in one transaction.
 *
 * Admin-owned data is never overwritten: meal-unit flags and floor / canteen / cost-center
 * mapping stay as the HC admin set them (BR-60, BR-62). Nodes gone from DMP are deactivated, not
 * deleted (rule 7).
 */
@Injectable()
export class OrgSyncService {
  private readonly logger = new Logger(OrgSyncService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly clock: ClockService,
    private readonly audit: AuditService,
    private readonly bootstrap: SystemAdminBootstrap,
    private readonly settings: SettingsService,
    @Inject(DMP_CLIENT) private readonly dmp: DmpClient,
  ) {}

  /** `actorId` is null for the scheduled job; a manual trigger is audited. */
  async run(factoryId: string, actorId: string | null): Promise<OrgSyncResult> {
    const running = await this.prisma.syncRun.findFirst({
      where: { type: 'ORG_EMPLOYEES', status: 'RUNNING' },
      orderBy: { startedAt: 'desc' },
    });
    // A run that died mid-way stays RUNNING; after 30 minutes it no longer blocks new runs.
    if (running && this.clock.now().getTime() - running.startedAt.getTime() < 30 * 60_000) {
      throw invalidState('An org sync is already running');
    }

    const run = await this.prisma.syncRun.create({
      data: { type: 'ORG_EMPLOYEES', startedAt: this.clock.now(), status: 'RUNNING' },
    });
    if (actorId) {
      await this.audit.log({
        actorId,
        action: 'sync.orgEmployees.triggered',
        entityType: 'SyncRun',
        entityId: run.id,
      });
    }

    try {
      const [nodes, employees] = [await this.dmp.getOrgTree(), await this.dmp.getEmployees()];
      const details = await this.prisma.$transaction(
        async (tx) => {
          const lock = await tx.$queryRaw<Array<{ locked: boolean }>>`
            SELECT pg_try_advisory_xact_lock(${SYNC_LOCK_KEY}) AS locked`;
          if (!lock[0]?.locked) throw new Error('Another org sync holds the lock');
          return this.write(tx, factoryId, nodes, employees);
        },
        { timeout: 120_000, maxWait: 10_000 },
      );
      await this.prisma.syncRun.update({
        where: { id: run.id },
        data: {
          status: 'SUCCESS',
          finishedAt: this.clock.now(),
          recordCount: details.orgUnits.total + details.employees.total,
          details: details as unknown as Prisma.InputJsonValue,
        },
      });
      // Plan section 3: the first system admin may only exist after the first employee sync.
      await this.bootstrap.ensure();
      return { syncRunId: run.id, status: 'SUCCESS', details, error: null };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      this.logger.error(`Org sync ${run.id} failed: ${message}`);
      await this.prisma.syncRun.update({
        where: { id: run.id },
        data: { status: 'FAILED', finishedAt: this.clock.now(), error: message.slice(0, 2000) },
      });
      return { syncRunId: run.id, status: 'FAILED', details: null, error: message };
    }
  }

  /** Same as `run` but throws a 502 on failure, for the manual trigger endpoint. */
  async runOrThrow(factoryId: string, actorId: string): Promise<OrgSyncResult> {
    const result = await this.run(factoryId, actorId);
    if (result.status === 'FAILED') {
      throw new ApiException(HttpStatus.BAD_GATEWAY, 'DMP_UNAVAILABLE', 'Org sync failed', {
        details: { syncRunId: result.syncRunId, error: result.error },
      });
    }
    return result;
  }

  private async write(
    tx: Prisma.TransactionClient,
    factoryId: string,
    dmpNodes: DmpOrgNode[],
    dmpEmployees: DmpEmployee[],
  ): Promise<OrgSyncDetails> {
    // BR-61: validate the tree and compute levels 1..6 before writing anything.
    const tree = buildDmpTree(dmpNodes);
    if (!tree.ok) {
      throw new Error(`DMP org tree rejected: ${JSON.stringify(tree.errors.slice(0, 10))}`);
    }
    const today = toVnDateTime(this.clock.now()).date;

    // --- Org units ---------------------------------------------------------------------------
    const existing = await tx.orgUnit.findMany({ where: { factoryId } });
    const byCode = new Map(existing.map((u) => [u.dmpCode, u]));
    const resolved = new Map<string, { id: string; path: string }>();
    const orgUnits = { created: 0, updated: 0, deactivated: 0, total: tree.nodes.length };

    for (const node of tree.nodes) {
      const parent = node.parentCode ? resolved.get(node.parentCode)! : null;
      const current = byCode.get(node.code);
      const id = current?.id ?? `ou_${randomUUID()}`;
      const path = `${parent?.path ?? '/'}${id}/`;
      resolved.set(node.code, { id, path });
      const data = {
        name: node.name,
        level: node.level,
        dmpLevelName: node.levelName,
        parentId: parent?.id ?? null,
        path,
        dmpCostCenterCode: node.costCenterCode,
        active: true,
      };
      if (!current) {
        await tx.orgUnit.create({ data: { id, factoryId, dmpCode: node.code, ...data } });
        orgUnits.created += 1;
      } else if (
        (Object.keys(data) as Array<keyof typeof data>).some((k) => current[k] !== data[k])
      ) {
        await tx.orgUnit.update({ where: { id }, data });
        orgUnits.updated += 1;
      }
    }
    const seen = new Set(tree.nodes.map((n) => n.code));
    const gone = existing.filter((u) => u.active && !seen.has(u.dmpCode)).map((u) => u.id);
    if (gone.length > 0) {
      orgUnits.deactivated = (
        await tx.orgUnit.updateMany({ where: { id: { in: gone } }, data: { active: false } })
      ).count;
    }

    // --- Cost centers supplied by DMP (BR-62: DMP is the default, admin mapping overrides) ------
    const ccCodes = new Set(
      [...dmpEmployees.map((e) => e.costCenterCode), ...dmpNodes.map((n) => n.costCenterCode)]
        .filter((c): c is string => !!c)
        .map((c) => c.trim()),
    );
    const knownCc = new Set(
      (await tx.costCenter.findMany({ where: { factoryId }, select: { code: true } })).map(
        (c) => c.code,
      ),
    );
    const newCc = [...ccCodes].filter((c) => !knownCc.has(c));
    if (newCc.length > 0) {
      await tx.costCenter.createMany({
        data: newCc.map((code) => ({ factoryId, code, name: code, source: 'DMP' as const })),
        skipDuplicates: true,
      });
    }

    // --- Employees ---------------------------------------------------------------------------
    const current = await tx.employee.findMany({
      where: { orgUnit: { factoryId } },
      select: {
        id: true,
        employeeCode: true,
        name: true,
        email: true,
        orgUnitId: true,
        dmpCostCenterCode: true,
        status: true,
        startDate: true,
        endDate: true,
      },
    });
    const empByCode = new Map(current.map((e) => [e.employeeCode, e]));
    const employees = { created: 0, updated: 0, left: 0, total: 0 };
    const skipped: OrgSyncDetails['skippedEmployees'] = [];
    const toCreate: Prisma.EmployeeCreateManyInput[] = [];
    const seenCodes = new Set<string>();

    for (const e of dmpEmployees) {
      const code = e.employeeCode.trim();
      if (!code || seenCodes.has(code)) continue;
      seenCodes.add(code);
      const unit = resolved.get(e.orgUnitCode);
      if (!unit) {
        skipped.push({ employeeCode: code, orgUnitCode: e.orgUnitCode });
        continue;
      }
      employees.total += 1;
      const status = e.endDate && e.endDate <= today ? ('LEFT' as const) : ('ACTIVE' as const);
      const data = {
        name: e.name,
        email: e.email?.trim() || null,
        orgUnitId: unit.id,
        dmpCostCenterCode: e.costCenterCode?.trim() || null,
        status,
        startDate: e.startDate ? new Date(`${e.startDate}T00:00:00.000Z`) : null,
        endDate: e.endDate ? new Date(`${e.endDate}T00:00:00.000Z`) : null,
      };
      const prev = empByCode.get(code);
      if (!prev) {
        // No PIN yet: a new employee signs in with Microsoft or gets a PIN from an admin.
        toCreate.push({ employeeCode: code, ...data, pinMustChange: true });
        employees.created += 1;
        if (status === 'LEFT') employees.left += 1;
        continue;
      }
      const changed =
        prev.name !== data.name ||
        prev.email !== data.email ||
        prev.orgUnitId !== data.orgUnitId ||
        prev.dmpCostCenterCode !== data.dmpCostCenterCode ||
        prev.status !== data.status ||
        prev.startDate?.getTime() !== data.startDate?.getTime() ||
        prev.endDate?.getTime() !== data.endDate?.getTime();
      if (changed) {
        await tx.employee.update({ where: { id: prev.id }, data });
        employees.updated += 1;
        if (prev.status === 'ACTIVE' && status === 'LEFT') employees.left += 1;
      }
    }
    if (toCreate.length > 0) await tx.employee.createMany({ data: toCreate });

    // BR-60: meal units follow the (possibly moved) tree.
    const mealUnitsRecomputed = await recomputeMealUnits(tx, factoryId);

    // BR-36: leavers lose their meals from their end date.
    const mealsCancelledForLeavers = await cancelLeaversMeals(tx, this.audit, {
      factoryId,
      now: this.clock.now(),
      lockTime: await this.settings.get(factoryId, 'day.lockTime'),
    });

    return {
      orgUnits,
      employees,
      costCentersCreated: newCc.length,
      mealUnitsRecomputed,
      skippedEmployees: skipped.slice(0, 200),
      // TODO(BR-36): what an employee missing from DMP means is open (open-questions Q13).
      missingFromDmp: current.filter((e) => !seenCodes.has(e.employeeCode)).length,
      mealsCancelledForLeavers,
    };
  }
}
