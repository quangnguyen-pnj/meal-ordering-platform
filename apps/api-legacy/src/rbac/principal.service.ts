import { Injectable } from '@nestjs/common';
import type { AuthMethod } from '@prisma/client';
import type { Role, SessionUser } from '@meal/shared';
import { PrismaService } from '../prisma/prisma.service';
import type { RequestPrincipal } from './rbac.types';

@Injectable()
export class PrincipalService {
  constructor(private readonly prisma: PrismaService) {}

  /** Builds the guard's view of a signed-in employee, or null when they may no longer sign in. */
  async load(
    employeeId: string,
    session: { id: string; method: AuthMethod },
  ): Promise<RequestPrincipal | null> {
    const e = await this.prisma.employee.findUnique({
      where: { id: employeeId },
      include: {
        orgUnit: { select: { factoryId: true } },
        mealUnit: { select: { path: true } },
        roleAssignments: {
          where: { revokedAt: null },
          include: { orgUnit: { select: { path: true, active: true } } },
        },
      },
    });
    if (!e || e.status !== 'ACTIVE') return null;

    const roles = new Set<Role>(['EMPLOYEE']);
    const deptAdminOrgUnitIds: string[] = [];
    const deptAdminPaths: string[] = [];
    const hcFactoryIds: string[] = [];
    for (const a of e.roleAssignments) {
      roles.add(a.role);
      if (a.role === 'HC_ADMIN') hcFactoryIds.push(a.factoryId);
      if (a.role === 'DEPT_ADMIN' && a.orgUnitId && a.orgUnit?.active) {
        deptAdminOrgUnitIds.push(a.orgUnitId);
        deptAdminPaths.push(a.orgUnit.path);
      }
    }

    return {
      sessionId: session.id,
      employeeId: e.id,
      employeeCode: e.employeeCode,
      name: e.name,
      email: e.email,
      factoryId: e.orgUnit.factoryId,
      mealUnitId: e.mealUnitId,
      mealUnitPath: e.mealUnit?.path ?? null,
      roles: [...roles],
      deptAdminOrgUnitIds,
      deptAdminPaths,
      hcFactoryIds,
      mustChangePin: session.method === 'PIN' && e.pinMustChange,
    };
  }

  toSessionUser(p: RequestPrincipal): SessionUser {
    return {
      id: p.employeeId,
      employeeCode: p.employeeCode,
      name: p.name,
      email: p.email,
      roles: p.roles,
      factoryId: p.factoryId,
      mealUnitId: p.mealUnitId,
      deptAdminOrgUnitIds: p.deptAdminOrgUnitIds,
      pinMustChange: p.mustChangePin,
    };
  }
}
