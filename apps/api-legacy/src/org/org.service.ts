import { Injectable } from '@nestjs/common';
import type { Prisma } from '@prisma/client';
import {
  resolveMealUnitId,
  resolveMealUnitMapping,
  resolveNodeMapping,
  type EmployeeMappingView,
  type OrgUnitUpdate,
  type OrgUnitView,
} from '@meal/shared';
import { AuditService } from '../audit/audit.service';
import { ClockService } from '../clock/clock.service';
import { invalid, invalidState, notFound } from '../common/api-error';
import { PrismaService } from '../prisma/prisma.service';
import {
  EMPLOYEE_FOR_MAPPING_SELECT,
  costCenterIdsByCode,
  describeEmployees,
  loadMasterNames,
  loadOrg,
  recomputeMealUnits,
} from './org-index';

const MAPPING_FIELDS = ['isMealUnit', 'floorId', 'canteenId', 'costCenterId'] as const;

@Injectable()
export class OrgService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
    private readonly clock: ClockService,
  ) {}

  /** The 6-level tree with own and inherited mapping (BR-61, BR-62) and dept admins. */
  async tree(factoryId: string): Promise<OrgUnitView[]> {
    const org = await loadOrg(this.prisma, factoryId);
    const [counts, admins] = await Promise.all([
      this.prisma.employee.groupBy({
        by: ['orgUnitId'],
        where: { status: 'ACTIVE', orgUnit: { factoryId } },
        _count: { _all: true },
      }),
      this.prisma.roleAssignment.findMany({
        where: { factoryId, role: 'DEPT_ADMIN', revokedAt: null, orgUnitId: { not: null } },
        select: {
          id: true,
          orgUnitId: true,
          employee: { select: { employeeCode: true, name: true } },
        },
        orderBy: { createdAt: 'asc' },
      }),
    ]);
    const headcount = new Map(counts.map((c) => [c.orgUnitId, c._count._all]));

    return org.nodes.map((n) => {
      const r = resolveNodeMapping(n.id, org.index);
      const view = (m: typeof r.floor) => ({
        id: m.value,
        sourceNodeId: m.sourceNodeId,
        inherited: m.inherited,
      });
      return {
        id: n.id,
        dmpCode: n.dmpCode,
        name: n.name,
        level: n.level,
        dmpLevelName: n.dmpLevelName,
        parentId: n.parentId,
        active: n.active,
        isMealUnit: n.isMealUnit,
        own: { floorId: n.floorId, canteenId: n.canteenId, costCenterId: n.costCenterId },
        resolved: {
          floor: view(r.floor),
          canteen: view(r.canteen),
          costCenter: view(r.costCenter),
        },
        mealUnitId: resolveMealUnitId(n.id, org.index),
        mealUnitValid: n.isMealUnit ? resolveMealUnitMapping(n.id, org.index).valid : null,
        directHeadcount: headcount.get(n.id) ?? 0,
        deptAdmins: admins
          .filter((a) => a.orgUnitId === n.id)
          .map((a) => ({
            assignmentId: a.id,
            employeeCode: a.employee.employeeCode,
            name: a.employee.name,
          })),
      };
    });
  }

  /** BR-60 / BR-62: meal-unit flag and own mapping of one node. Audited; meal units recomputed. */
  async updateUnit(actorId: string, factoryId: string, unitId: string, patch: OrgUnitUpdate) {
    return this.prisma.$transaction(async (tx) => {
      const unit = await tx.orgUnit.findUnique({ where: { id: unitId } });
      if (!unit || unit.factoryId !== factoryId) throw notFound('Org unit not found');
      await this.assertMasterRefs(tx, factoryId, patch);

      const before: Record<string, unknown> = {};
      const after: Record<string, unknown> = {};
      for (const field of MAPPING_FIELDS) {
        if (patch[field] !== undefined && patch[field] !== unit[field]) {
          before[field] = unit[field];
          after[field] = patch[field];
        }
      }
      if (Object.keys(after).length === 0) return { changed: false, mealUnitsRecomputed: 0 };

      await tx.orgUnit.update({ where: { id: unitId }, data: after });
      await this.audit.log(
        {
          actorId,
          action: 'orgUnit.mapping.updated',
          entityType: 'OrgUnit',
          entityId: unitId,
          before: before as Prisma.InputJsonValue,
          after: after as Prisma.InputJsonValue,
        },
        tx,
      );
      const mealUnitsRecomputed =
        'isMealUnit' in after ? await recomputeMealUnits(tx, factoryId) : 0;
      return { changed: true, mealUnitsRecomputed };
    });
  }

  /** Referenced floor / canteen / cost center must exist in the factory and be active. */
  private async assertMasterRefs(
    tx: Prisma.TransactionClient,
    factoryId: string,
    patch: OrgUnitUpdate,
  ): Promise<void> {
    const checks: Array<[string, Promise<{ active: boolean; factoryId: string } | null>]> = [];
    if (patch.floorId)
      checks.push(['floorId', tx.floor.findUnique({ where: { id: patch.floorId } })]);
    if (patch.canteenId)
      checks.push(['canteenId', tx.canteen.findUnique({ where: { id: patch.canteenId } })]);
    if (patch.costCenterId)
      checks.push([
        'costCenterId',
        tx.costCenter.findUnique({ where: { id: patch.costCenterId } }),
      ]);
    for (const [field, query] of checks) {
      const row = await query;
      if (!row || row.factoryId !== factoryId || !row.active) {
        throw invalid('Unknown or inactive master data', [{ path: field, message: 'inactive' }]);
      }
    }
  }

  /** BR-60 / BR-63: employees who cannot register (no meal unit, or an invalid one). */
  async unassigned(factoryId: string): Promise<EmployeeMappingView[]> {
    return (await this.describeActive(factoryId)).filter((e) => e.mealStatus !== 'OK');
  }

  /** BR-63: employees with no cost center (they can still register). */
  async missingCostCenter(factoryId: string): Promise<EmployeeMappingView[]> {
    return (await this.describeActive(factoryId)).filter((e) => e.costCenterId === null);
  }

  private async describeActive(factoryId: string): Promise<EmployeeMappingView[]> {
    const [org, employees, ccByCode, names] = await Promise.all([
      loadOrg(this.prisma, factoryId),
      this.prisma.employee.findMany({
        where: { status: 'ACTIVE', orgUnit: { factoryId } },
        select: EMPLOYEE_FOR_MAPPING_SELECT,
        orderBy: { employeeCode: 'asc' },
      }),
      costCenterIdsByCode(this.prisma, factoryId),
      loadMasterNames(this.prisma, factoryId),
    ]);
    return describeEmployees(org, employees, ccByCode, names);
  }

  /** Plan section 3: HC and system admins assign dept admins to org nodes (and their children). */
  async assignDeptAdmin(actorId: string, factoryId: string, unitId: string, employeeCode: string) {
    const unit = await this.prisma.orgUnit.findUnique({ where: { id: unitId } });
    if (!unit || unit.factoryId !== factoryId) throw notFound('Org unit not found');
    if (!unit.active) throw invalidState('Org unit is no longer active');
    const employee = await this.prisma.employee.findUnique({ where: { employeeCode } });
    if (!employee || employee.status !== 'ACTIVE') {
      throw invalid('Unknown or inactive employee', [{ path: 'employeeCode', message: 'unknown' }]);
    }
    const existing = await this.prisma.roleAssignment.findFirst({
      where: { employeeId: employee.id, role: 'DEPT_ADMIN', orgUnitId: unitId, revokedAt: null },
    });
    if (existing) return { id: existing.id, created: false };

    return this.prisma.$transaction(async (tx) => {
      const row = await tx.roleAssignment.create({
        data: { employeeId: employee.id, role: 'DEPT_ADMIN', factoryId, orgUnitId: unitId },
      });
      await this.audit.log(
        {
          actorId,
          action: 'role.assigned',
          entityType: 'Employee',
          entityId: employee.id,
          after: { role: 'DEPT_ADMIN', orgUnitId: unitId, assignmentId: row.id },
        },
        tx,
      );
      return { id: row.id, created: true };
    });
  }

  async revokeDeptAdmin(actorId: string, factoryId: string, assignmentId: string) {
    const row = await this.prisma.roleAssignment.findUnique({ where: { id: assignmentId } });
    if (!row || row.factoryId !== factoryId || row.role !== 'DEPT_ADMIN') {
      throw notFound('Assignment not found');
    }
    if (row.revokedAt) return { revoked: false };
    await this.prisma.$transaction(async (tx) => {
      await tx.roleAssignment.update({
        where: { id: assignmentId },
        data: { revokedAt: this.clock.now() },
      });
      await this.audit.log(
        {
          actorId,
          action: 'role.revoked',
          entityType: 'Employee',
          entityId: row.employeeId,
          before: { role: 'DEPT_ADMIN', orgUnitId: row.orgUnitId, assignmentId },
        },
        tx,
      );
    });
    return { revoked: true };
  }
}
