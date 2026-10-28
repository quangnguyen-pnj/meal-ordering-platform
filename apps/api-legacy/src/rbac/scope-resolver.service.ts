import { Injectable } from '@nestjs/common';
import type { ResourceScope } from '@meal/shared';
import { PrismaService } from '../prisma/prisma.service';
import type { AuthedRequest, RequestPrincipal, ScopeSpec } from './rbac.types';

/** Turns a route's declarative ScopeSpec into a concrete ResourceScope for `authorize`. */
@Injectable()
export class ScopeResolver {
  constructor(private readonly prisma: PrismaService) {}

  /** Returns null when the referenced resource does not exist. `list` is handled by the guard. */
  async resolve(
    spec: Exclude<ScopeSpec, { type: 'list' }>,
    principal: RequestPrincipal,
    req: AuthedRequest,
  ): Promise<ResourceScope | null> {
    switch (spec.type) {
      case 'system':
        return { kind: 'system' };
      case 'factory':
        return { kind: 'factory', factoryId: principal.factoryId };
      case 'self':
        return { kind: 'self', employeeId: principal.employeeId };
      case 'employeeParam': {
        const code = req.params[spec.param];
        if (typeof code !== 'string') return null;
        const employee = await this.prisma.employee.findUnique({
          where: { employeeCode: code },
          select: { orgUnit: { select: { path: true, factoryId: true } } },
        });
        return employee
          ? {
              kind: 'org',
              factoryId: employee.orgUnit.factoryId,
              orgUnitPath: employee.orgUnit.path,
            }
          : null;
      }
      case 'orgUnitParam': {
        const id = req.params[spec.param];
        if (typeof id !== 'string') return null;
        const unit = await this.prisma.orgUnit.findUnique({
          where: { id },
          select: { path: true, factoryId: true },
        });
        return unit ? { kind: 'org', factoryId: unit.factoryId, orgUnitPath: unit.path } : null;
      }
    }
  }
}
