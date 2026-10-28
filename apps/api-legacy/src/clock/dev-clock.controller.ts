import { Body, Controller, Get, Put } from '@nestjs/common';
import { z } from 'zod';
import { AuditService } from '../audit/audit.service';
import { ZodValidationPipe } from '../common/zod-validation.pipe';
import { CurrentPrincipal, RequirePermission } from '../rbac/decorators';
import type { RequestPrincipal } from '../rbac/rbac.types';
import { ClockService } from './clock.service';

const SetClockSchema = z.object({ at: z.string().datetime({ offset: true }).nullable() });

/**
 * Fake-time controls for tests and demos. Only registered when fake time is allowed (never in
 * production); system admins only.
 */
@Controller('dev/clock')
export class DevClockController {
  constructor(
    private readonly clock: ClockService,
    private readonly audit: AuditService,
  ) {}

  @RequirePermission('integration.configure', { type: 'system' })
  @Get()
  get() {
    return { fake: this.clock.isFake(), now: this.clock.now().toISOString() };
  }

  /** Body `{ "at": "2026-10-22T23:59:30+07:00" }` freezes time; `{ "at": null }` returns to real time. */
  @RequirePermission('integration.configure', { type: 'system' })
  @Put()
  async set(
    @Body(new ZodValidationPipe(SetClockSchema)) body: z.infer<typeof SetClockSchema>,
    @CurrentPrincipal() principal: RequestPrincipal,
  ) {
    const before = this.clock.isFake() ? this.clock.now().toISOString() : null;
    if (body.at === null) this.clock.clearFakeTime();
    else this.clock.setFakeTime(new Date(body.at));
    await this.audit.log({
      actorId: principal.employeeId,
      action: 'dev.clock.set',
      entityType: 'Clock',
      entityId: 'fake-time',
      before: { fakeNow: before },
      after: { fakeNow: body.at },
    });
    return this.get();
  }
}
