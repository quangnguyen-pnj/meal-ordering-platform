import { Inject, Injectable, Logger, type OnApplicationBootstrap } from '@nestjs/common';
import { AuditService } from '../audit/audit.service';
import { ENV, type AppEnv } from '../config/env';
import { PrismaService } from '../prisma/prisma.service';

/**
 * Plan section 3: the first system admin comes from an environment variable, and later system
 * admins are assigned by system admins in the UI. On startup (and after each employee sync,
 * from P1) this makes sure the configured employee holds the SYS_ADMIN role.
 */
@Injectable()
export class SystemAdminBootstrap implements OnApplicationBootstrap {
  private readonly logger = new Logger(SystemAdminBootstrap.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
    @Inject(ENV) private readonly env: AppEnv,
  ) {}

  async onApplicationBootstrap(): Promise<void> {
    await this.ensure();
  }

  /** Returns true when the role was newly granted. Safe to call repeatedly. */
  async ensure(): Promise<boolean> {
    const code = this.env.FIRST_SYSTEM_ADMIN_CODE;
    if (!code) return false;

    const employee = await this.prisma.employee.findUnique({
      where: { employeeCode: code },
      include: { orgUnit: { select: { factoryId: true } }, roleAssignments: true },
    });
    if (!employee) {
      this.logger.warn(
        `FIRST_SYSTEM_ADMIN_CODE=${code} matches no employee yet; will retry after the next employee sync`,
      );
      return false;
    }
    // Any SYS_ADMIN row counts, revoked ones too: a system admin who revoked the first admin must
    // not see the role come back at the next restart.
    if (employee.roleAssignments.some((a) => a.role === 'SYS_ADMIN')) return false;

    await this.prisma.roleAssignment.create({
      data: { employeeId: employee.id, role: 'SYS_ADMIN', factoryId: employee.orgUnit.factoryId },
    });
    await this.audit.log({
      actorId: null,
      action: 'role.assigned',
      entityType: 'Employee',
      entityId: employee.id,
      after: { role: 'SYS_ADMIN', source: 'FIRST_SYSTEM_ADMIN_CODE' },
    });
    this.logger.log(`Granted SYS_ADMIN to ${code} from FIRST_SYSTEM_ADMIN_CODE`);
    return true;
  }
}
