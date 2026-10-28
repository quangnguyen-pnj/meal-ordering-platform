import { Body, Controller, Get, HttpCode, HttpStatus, Param, Post } from '@nestjs/common';
import { PrivilegedRoleSchema } from '@meal/shared';
import type { z } from 'zod';
import { AuditService } from '../audit/audit.service';
import { ClockService } from '../clock/clock.service';
import { invalid, invalidState, notFound } from '../common/api-error';
import { ZodValidationPipe } from '../common/zod-validation.pipe';
import { PrismaService } from '../prisma/prisma.service';
import { CurrentPrincipal, RequirePermission } from '../rbac/decorators';
import type { RequestPrincipal } from '../rbac/rbac.types';

@Controller('system')
export class SystemController {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
    private readonly clock: ClockService,
  ) {}

  /** Sync logs: HC admin (factory) and system admin only (plan section 3). */
  @RequirePermission('sync.manage', { type: 'factory' })
  @Get('sync-runs')
  async syncRuns() {
    const items = await this.prisma.syncRun.findMany({ orderBy: { startedAt: 'desc' }, take: 50 });
    return { items };
  }

  /** HC admins and system admins (plan section 3: assigned by system admins only). */
  @RequirePermission('roles.assignPrivileged', { type: 'system' })
  @Get('roles')
  async roles() {
    const rows = await this.prisma.roleAssignment.findMany({
      where: { role: { in: ['HC_ADMIN', 'SYS_ADMIN'] }, revokedAt: null },
      include: { employee: { select: { employeeCode: true, name: true } } },
      orderBy: [{ role: 'asc' }, { createdAt: 'asc' }],
    });
    return {
      items: rows.map((r) => ({
        id: r.id,
        role: r.role,
        employeeCode: r.employee.employeeCode,
        name: r.employee.name,
        createdAt: r.createdAt.toISOString(),
      })),
    };
  }

  @RequirePermission('roles.assignPrivileged', { type: 'system' })
  @Post('roles')
  async assign(
    @Body(new ZodValidationPipe(PrivilegedRoleSchema)) body: z.infer<typeof PrivilegedRoleSchema>,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    const employee = await this.prisma.employee.findUnique({
      where: { employeeCode: body.employeeCode },
      include: { orgUnit: { select: { factoryId: true } } },
    });
    if (!employee || employee.status !== 'ACTIVE') {
      throw invalid('Unknown or inactive employee', [{ path: 'employeeCode', message: 'unknown' }]);
    }
    const existing = await this.prisma.roleAssignment.findFirst({
      where: { employeeId: employee.id, role: body.role, revokedAt: null },
    });
    if (existing) return { id: existing.id, created: false };
    return this.prisma.$transaction(async (tx) => {
      const row = await tx.roleAssignment.create({
        data: { employeeId: employee.id, role: body.role, factoryId: employee.orgUnit.factoryId },
      });
      await this.audit.log(
        {
          actorId: p.employeeId,
          action: 'role.assigned',
          entityType: 'Employee',
          entityId: employee.id,
          after: { role: body.role, assignmentId: row.id },
        },
        tx,
      );
      return { id: row.id, created: true };
    });
  }

  @RequirePermission('roles.assignPrivileged', { type: 'system' })
  @Post('roles/:id/revoke')
  @HttpCode(HttpStatus.OK)
  async revoke(@Param('id') id: string, @CurrentPrincipal() p: RequestPrincipal) {
    const row = await this.prisma.roleAssignment.findUnique({ where: { id } });
    if (!row || (row.role !== 'HC_ADMIN' && row.role !== 'SYS_ADMIN')) throw notFound();
    if (row.revokedAt) return { revoked: false };
    if (row.role === 'SYS_ADMIN') {
      const left = await this.prisma.roleAssignment.count({
        where: { role: 'SYS_ADMIN', revokedAt: null },
      });
      if (left <= 1) throw invalidState('The last system admin cannot be revoked');
    }
    await this.prisma.$transaction(async (tx) => {
      await tx.roleAssignment.update({ where: { id }, data: { revokedAt: this.clock.now() } });
      await this.audit.log(
        {
          actorId: p.employeeId,
          action: 'role.revoked',
          entityType: 'Employee',
          entityId: row.employeeId,
          before: { role: row.role, assignmentId: id },
        },
        tx,
      );
    });
    return { revoked: true };
  }
}
