import { HttpStatus, Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import type { CanteenInput, CostCenterInput, FloorInput, MasterItemView } from '@meal/shared';
import { AuditService } from '../audit/audit.service';
import { ApiException, conflict, notFound } from '../common/api-error';
import { PrismaService } from '../prisma/prisma.service';

export type MasterKind = 'canteen' | 'floor' | 'costCenter';

const ENTITY: Record<MasterKind, string> = {
  canteen: 'Canteen',
  floor: 'Floor',
  costCenter: 'CostCenter',
};

/**
 * BR-62: canteens, floors and cost centers are managed by the HC admin: add, edit, deactivate,
 * never delete. Codes are unique per factory and never change (imports and DMP refer to them).
 */
@Injectable()
export class MasterService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
  ) {}

  async list(kind: MasterKind, factoryId: string): Promise<MasterItemView[]> {
    switch (kind) {
      case 'canteen': {
        const rows = await this.prisma.canteen.findMany({
          where: { factoryId },
          orderBy: { code: 'asc' },
          include: { _count: { select: { orgUnits: true } } },
        });
        return rows.map((r) => ({
          id: r.id,
          code: r.code,
          name: r.name,
          active: r.active,
          usageCount: r._count.orgUnits,
        }));
      }
      case 'floor': {
        const rows = await this.prisma.floor.findMany({
          where: { factoryId },
          orderBy: [{ sortOrder: 'asc' }, { code: 'asc' }],
          include: { _count: { select: { orgUnits: true, floorOverrideFor: true } } },
        });
        return rows.map((r) => ({
          id: r.id,
          code: r.code,
          name: r.name,
          active: r.active,
          sortOrder: r.sortOrder,
          usageCount: r._count.orgUnits + r._count.floorOverrideFor,
        }));
      }
      case 'costCenter': {
        const rows = await this.prisma.costCenter.findMany({
          where: { factoryId },
          orderBy: { code: 'asc' },
          include: { _count: { select: { orgUnits: true } } },
        });
        return rows.map((r) => ({
          id: r.id,
          code: r.code,
          name: r.name,
          active: r.active,
          source: r.source,
          usageCount: r._count.orgUnits,
        }));
      }
    }
  }

  async create(
    kind: MasterKind,
    actorId: string,
    factoryId: string,
    input: CanteenInput | FloorInput | CostCenterInput,
  ): Promise<{ id: string }> {
    try {
      return await this.prisma.$transaction(async (tx) => {
        let id: string;
        if (kind === 'canteen') {
          // MVP: one caterer per factory; a new canteen is served by the factory's active supplier.
          const supplier = await tx.supplier.findFirst({
            where: { factoryId, active: true },
            orderBy: { name: 'asc' },
          });
          if (!supplier) throw notFound('No active supplier for this factory');
          id = (await tx.canteen.create({ data: { ...input, factoryId, supplierId: supplier.id } }))
            .id;
        } else if (kind === 'floor') {
          id = (await tx.floor.create({ data: { ...(input as FloorInput), factoryId } })).id;
        } else {
          id = (await tx.costCenter.create({ data: { ...input, factoryId, source: 'MANUAL' } })).id;
        }
        await this.audit.log(
          {
            actorId,
            action: `${kind}.created`,
            entityType: ENTITY[kind],
            entityId: id,
            after: input as Prisma.InputJsonValue,
          },
          tx,
        );
        return { id };
      });
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') {
        throw conflict('Code already exists', [{ path: 'code', message: 'duplicate' }]);
      }
      throw error;
    }
  }

  /** Edits name / order / active. Deactivating is refused while org nodes or employees use it. */
  async update(
    kind: MasterKind,
    actorId: string,
    factoryId: string,
    id: string,
    patch: { name?: string; sortOrder?: number; active?: boolean },
  ): Promise<{ id: string }> {
    return this.prisma.$transaction(async (tx) => {
      const current =
        kind === 'canteen'
          ? await tx.canteen.findUnique({ where: { id } })
          : kind === 'floor'
            ? await tx.floor.findUnique({ where: { id } })
            : await tx.costCenter.findUnique({ where: { id } });
      if (!current || current.factoryId !== factoryId) throw notFound();

      if (patch.active === false && current.active) {
        // TODO(BR-62): the plan does not say what happens to nodes mapped to a deactivated item;
        // refusing keeps every mapping valid (open-questions Q14).
        const inUse = await this.usage(tx, kind, id);
        if (inUse > 0) {
          throw new ApiException(HttpStatus.CONFLICT, 'IN_USE', 'Still mapped', {
            details: { usageCount: inUse },
          });
        }
      }

      const data: Record<string, unknown> = {};
      const before: Record<string, unknown> = {};
      for (const [key, value] of Object.entries(patch)) {
        if (value === undefined || (key === 'sortOrder' && kind !== 'floor')) continue;
        const old = (current as Record<string, unknown>)[key];
        if (old !== value) {
          data[key] = value;
          before[key] = old;
        }
      }
      if (Object.keys(data).length === 0) return { id };

      if (kind === 'canteen') await tx.canteen.update({ where: { id }, data });
      else if (kind === 'floor') await tx.floor.update({ where: { id }, data });
      else await tx.costCenter.update({ where: { id }, data });

      await this.audit.log(
        {
          actorId,
          action: `${kind}.updated`,
          entityType: ENTITY[kind],
          entityId: id,
          before: before as Prisma.InputJsonValue,
          after: data as Prisma.InputJsonValue,
        },
        tx,
      );
      return { id };
    });
  }

  private async usage(tx: Prisma.TransactionClient, kind: MasterKind, id: string) {
    if (kind === 'canteen') {
      return (
        (await tx.orgUnit.count({ where: { canteenId: id } })) +
        (await tx.menuWeek.count({ where: { canteenId: id, status: { not: 'CLOSED' } } }))
      );
    }
    if (kind === 'floor') {
      return (
        (await tx.orgUnit.count({ where: { floorId: id } })) +
        (await tx.employee.count({ where: { floorOverrideId: id } }))
      );
    }
    // Employee cost-center overrides are no longer read (cost center follows the org tree).
    return tx.orgUnit.count({ where: { costCenterId: id } });
  }

  async supplier(factoryId: string) {
    const s = await this.prisma.supplier.findFirst({
      where: { factoryId, active: true },
      orderBy: { name: 'asc' },
    });
    if (!s) throw notFound('No active supplier');
    return { id: s.id, name: s.name, unitPrice: Number(s.unitPrice) };
  }

  /** Plan section 3 "business settings: caterer, unit price". Audited. */
  async updateSupplier(
    actorId: string,
    factoryId: string,
    input: { name: string; unitPrice: number },
  ) {
    const current = await this.supplier(factoryId);
    await this.prisma.$transaction(async (tx) => {
      await tx.supplier.update({
        where: { id: current.id },
        data: { name: input.name, unitPrice: new Prisma.Decimal(input.unitPrice) },
      });
      await this.audit.log(
        {
          actorId,
          action: 'supplier.updated',
          entityType: 'Supplier',
          entityId: current.id,
          before: { name: current.name, unitPrice: current.unitPrice },
          after: input,
        },
        tx,
      );
    });
    return this.supplier(factoryId);
  }
}
