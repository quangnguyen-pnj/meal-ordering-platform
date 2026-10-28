import { Body, Controller, Get, Param, Put } from '@nestjs/common';
import { SettingUpdateSchema } from '@meal/shared';
import type { z } from 'zod';
import { ZodValidationPipe } from '../common/zod-validation.pipe';
import { CurrentPrincipal, RequirePermission } from '../rbac/decorators';
import type { RequestPrincipal } from '../rbac/rbac.types';
import { SettingsService } from './settings.service';

/** /admin/settings: the plan 6.4 keys (HC admin and system admin). */
@Controller('admin/settings')
export class SettingsController {
  constructor(private readonly settings: SettingsService) {}

  @RequirePermission('settings.manage', { type: 'factory' })
  @Get()
  async list(@CurrentPrincipal() p: RequestPrincipal) {
    return { items: await this.settings.list(p.factoryId) };
  }

  @RequirePermission('settings.manage', { type: 'factory' })
  @Put(':key')
  update(
    @Param('key') key: string,
    @Body(new ZodValidationPipe(SettingUpdateSchema)) body: z.infer<typeof SettingUpdateSchema>,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.settings.set(p.employeeId, p.factoryId, key, body.value);
  }
}
