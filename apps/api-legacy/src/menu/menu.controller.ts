import {
  Body,
  Controller,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  Post,
  Put,
  Query,
} from '@nestjs/common';
import {
  DateStringSchema,
  MenuWeekSaveSchema,
  upcomingWeekStart,
  type MenuWeekSave,
} from '@meal/shared';
import { z } from 'zod';
import { ClockService } from '../clock/clock.service';
import { ZodValidationPipe } from '../common/zod-validation.pipe';
import { CurrentPrincipal, RequirePermission } from '../rbac/decorators';
import type { RequestPrincipal } from '../rbac/rbac.types';
import { MenuService } from './menu.service';

const AdminWeekQuerySchema = z.object({
  canteenId: z.string().min(1),
  weekStart: DateStringSchema,
});
const MyWeekQuerySchema = z.object({ weekStart: DateStringSchema.optional() });

const MANAGE = ['menu.manage', { type: 'factory' }] as const;

/** /admin/menu: build, save and publish a canteen's weekly menu (HC admin, BR-11..BR-15). */
@Controller('admin/menu')
export class MenuAdminController {
  constructor(
    private readonly menus: MenuService,
    private readonly clock: ClockService,
  ) {}

  @RequirePermission(...MANAGE)
  @Get('canteens')
  async canteens(@CurrentPrincipal() p: RequestPrincipal) {
    return {
      items: await this.menus.canteens(p.factoryId),
      upcomingWeekStart: upcomingWeekStart(this.clock.now()),
    };
  }

  @RequirePermission(...MANAGE)
  @Get('weeks')
  week(
    @Query(new ZodValidationPipe(AdminWeekQuerySchema)) q: z.infer<typeof AdminWeekQuerySchema>,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.menus.adminWeek(p.factoryId, q.canteenId, q.weekStart);
  }

  @RequirePermission(...MANAGE)
  @Put('weeks')
  save(
    @Body(new ZodValidationPipe(MenuWeekSaveSchema)) body: MenuWeekSave,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.menus.save(p.employeeId, p.factoryId, body);
  }

  /** Saves the submitted days and publishes in one step (no separate draft save needed). */
  @RequirePermission(...MANAGE)
  @Post('weeks/publish')
  @HttpCode(HttpStatus.OK)
  saveAndPublish(
    @Body(new ZodValidationPipe(MenuWeekSaveSchema)) body: MenuWeekSave,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.menus.saveAndPublish(p.employeeId, p.factoryId, body);
  }

  @RequirePermission(...MANAGE)
  @Post('weeks/:id/publish')
  @HttpCode(HttpStatus.OK)
  publish(@Param('id') id: string, @CurrentPrincipal() p: RequestPrincipal) {
    return this.menus.publish(p.employeeId, p.factoryId, id);
  }
}

/** The signed-in employee's published menu (BR-02, BR-15). */
@Controller('menu')
export class MyMenuController {
  constructor(
    private readonly menus: MenuService,
    private readonly clock: ClockService,
  ) {}

  @RequirePermission('menu.registerOwn', { type: 'self' })
  @Get('my-week')
  myWeek(
    @Query(new ZodValidationPipe(MyWeekQuerySchema)) q: z.infer<typeof MyWeekQuerySchema>,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.menus.myWeek(
      p.employeeId,
      p.factoryId,
      q.weekStart ?? upcomingWeekStart(this.clock.now()),
    );
  }
}
