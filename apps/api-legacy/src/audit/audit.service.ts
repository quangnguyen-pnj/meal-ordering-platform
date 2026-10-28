import { Injectable } from '@nestjs/common';
import type { Prisma } from '@prisma/client';
import { ClockService } from '../clock/clock.service';
import { PrismaService } from '../prisma/prisma.service';

export interface AuditEntry {
  /** Null for automatic system actions. */
  actorId: string | null;
  action: string;
  entityType: string;
  entityId: string;
  before?: Prisma.InputJsonValue;
  after?: Prisma.InputJsonValue;
  reason?: string;
}

/**
 * BR-70: who, when, before, after, reason. Append-only: there is deliberately no update or
 * delete method. Never put secrets (PINs, tokens) into before/after.
 */
@Injectable()
export class AuditService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly clock: ClockService,
  ) {}

  async log(entry: AuditEntry, tx?: Prisma.TransactionClient): Promise<void> {
    const db = tx ?? this.prisma;
    await db.auditLog.create({
      data: {
        actorId: entry.actorId,
        action: entry.action,
        entityType: entry.entityType,
        entityId: entry.entityId,
        before: entry.before,
        after: entry.after,
        reason: entry.reason,
        at: this.clock.now(),
      },
    });
  }
}
