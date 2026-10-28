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
  Req,
  Res,
  UploadedFile,
  UseInterceptors,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import {
  DateStringSchema,
  DaySelectionSchema,
  ExtraMealSchema,
  GridDaySaveSchema,
  MyWeekSaveSchema,
  type DaySelection,
  type ExtraMeal,
  type GridDaySave,
  type MyWeekSave,
  upcomingWeekStart,
} from '@meal/shared';
import type { Response } from 'express';
import { z } from 'zod';
import { ClockService } from '../clock/clock.service';
import { invalid } from '../common/api-error';
import { ZodValidationPipe } from '../common/zod-validation.pipe';
import { CurrentPrincipal, RequirePermission } from '../rbac/decorators';
import type { AuthedRequest, RequestPrincipal } from '../rbac/rbac.types';
import { AdminRegistrationsService } from './admin-registrations.service';
import { MyRegistrationsService } from './my-registrations.service';
import { RegistrationImportService } from './registration-import.service';

const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

const DateParam = new ZodValidationPipe(DateStringSchema);
const WeekQuerySchema = z.object({ weekStart: DateStringSchema.optional() });
const DayQuerySchema = z.object({ date: DateStringSchema.optional() });
const HistoryQuerySchema = z.object({
  from: DateStringSchema.optional(),
  to: DateStringSchema.optional(),
});
const SearchQuerySchema = z.object({ q: z.string().trim().max(64).optional() });
const AdminWeekQuerySchema = z.object({ weekStart: DateStringSchema });
const NotRegisteredQuerySchema = z.object({
  weekStart: DateStringSchema,
  date: DateStringSchema.optional(),
  /** Khối / Trung tâm / Phòng ban filter: employees at or below this node. */
  orgUnitId: z.string().min(1).optional(),
});
const ImportQuerySchema = GridQuerySchemaBase().extend({
  apply: z
    .enum(['true', 'false'])
    .optional()
    .transform((v) => v === 'true'),
});
function GridQuerySchemaBase() {
  return z.object({
    weekStart: DateStringSchema,
    canteenId: z.string().min(1).optional(),
  });
}
const GridQuerySchema = GridQuerySchemaBase();

const OWN = ['menu.registerOwn', { type: 'self' }] as const;

/** /week, /today, /history: the signed-in employee's own meals (BR-20 to BR-24). */
@Controller('registrations/me')
export class MyRegistrationsController {
  constructor(private readonly regs: MyRegistrationsService) {}

  @RequirePermission(...OWN)
  @Get('week')
  async week(
    @Query(new ZodValidationPipe(WeekQuerySchema)) q: z.infer<typeof WeekQuerySchema>,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.regs.week(p, q.weekStart ?? (await this.regs.defaultWeekStart(p)));
  }

  @RequirePermission(...OWN)
  @Put('week')
  saveWeek(
    @Body(new ZodValidationPipe(MyWeekSaveSchema)) body: MyWeekSave,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.regs.saveWeek(p, body);
  }

  @RequirePermission(...OWN)
  @Get('day')
  day(
    @Query(new ZodValidationPipe(DayQuerySchema)) q: z.infer<typeof DayQuerySchema>,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.regs.day(p, q.date);
  }

  @RequirePermission(...OWN)
  @Put('days/:date')
  saveDay(
    @Param('date', DateParam) date: string,
    @Body(new ZodValidationPipe(DaySelectionSchema)) body: DaySelection,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.regs.saveDay(p, date, body.lines);
  }

  @RequirePermission(...OWN)
  @Get('history')
  history(
    @Query(new ZodValidationPipe(HistoryQuerySchema)) q: z.infer<typeof HistoryQuerySchema>,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.regs.history(p, q.from, q.to);
  }
}

/** /team: colleagues' meals today (BR-27). Employees see their own meal unit only. */
@Controller('team')
export class TeamController {
  constructor(private readonly regs: MyRegistrationsService) {}

  /** The caller's own meal unit, which is always inside their OWN_UNIT grant. */
  @RequirePermission('team.view', { type: 'list' })
  @Get()
  own(
    @Query(new ZodValidationPipe(SearchQuerySchema)) q: z.infer<typeof SearchQuerySchema>,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.regs.team(p, null, q.q);
  }

  @RequirePermission('team.view', { type: 'orgUnitParam', param: 'orgUnitId' })
  @Get('units/:orgUnitId')
  unit(
    @Param('orgUnitId') orgUnitId: string,
    @Query(new ZodValidationPipe(SearchQuerySchema)) q: z.infer<typeof SearchQuerySchema>,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.regs.team(p, orgUnitId, q.q);
  }
}

const ON_BEHALF_UNIT = [
  'registration.onBehalf',
  { type: 'orgUnitParam', param: 'orgUnitId' },
] as const;

/** /admin/registrations and /admin/print (dept admins: their units; HC admins: the factory). */
@Controller('admin/registrations')
export class AdminRegistrationsController {
  constructor(
    private readonly admin: AdminRegistrationsService,
    private readonly imports: RegistrationImportService,
    private readonly clock: ClockService,
  ) {}

  @RequirePermission('registration.onBehalf', { type: 'list' })
  @Get('units')
  async units(@Req() req: AuthedRequest, @CurrentPrincipal() p: RequestPrincipal) {
    return {
      items: await this.admin.units(req.listScope ?? { kind: 'none' }, p.factoryId),
      // Screens default to the coming week by server time (fake time in dev and tests).
      upcomingWeekStart: upcomingWeekStart(this.clock.now()),
    };
  }

  @RequirePermission('registration.onBehalf', { type: 'list' })
  @Get('progress')
  progress(
    @Query(new ZodValidationPipe(AdminWeekQuerySchema)) q: z.infer<typeof AdminWeekQuerySchema>,
    @Req() req: AuthedRequest,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.admin.progress(req.listScope ?? { kind: 'none' }, p.factoryId, q.weekStart);
  }

  /** BR-21: who has not registered yet. */
  @RequirePermission('registration.notRegisteredList', { type: 'list' })
  @Get('not-registered')
  notRegistered(
    @Query(new ZodValidationPipe(NotRegisteredQuerySchema))
    q: z.infer<typeof NotRegisteredQuerySchema>,
    @Req() req: AuthedRequest,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.admin.notRegistered(
      req.listScope ?? { kind: 'none' },
      p.factoryId,
      q.weekStart,
      q.date ?? null,
      q.orgUnitId ?? null,
    );
  }

  @RequirePermission('registration.notRegisteredList', { type: 'list' })
  @Get('org-units')
  async orgUnits(@Req() req: AuthedRequest, @CurrentPrincipal() p: RequestPrincipal) {
    return { items: await this.admin.orgUnits(req.listScope ?? { kind: 'none' }, p.factoryId) };
  }

  @RequirePermission(...ON_BEHALF_UNIT)
  @Get('units/:orgUnitId/grid')
  grid(
    @Param('orgUnitId') orgUnitId: string,
    @Query(new ZodValidationPipe(GridQuerySchema)) q: z.infer<typeof GridQuerySchema>,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.admin.grid(p.factoryId, orgUnitId, q.weekStart, q.canteenId);
  }

  @RequirePermission(...ON_BEHALF_UNIT)
  @Put('units/:orgUnitId/days/:date')
  saveGridDay(
    @Param('orgUnitId') orgUnitId: string,
    @Param('date', DateParam) date: string,
    @Body(new ZodValidationPipe(GridDaySaveSchema)) body: GridDaySave,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.admin.saveGridDay(p.employeeId, p.factoryId, orgUnitId, date, body);
  }

  /** The unit's week as an Excel file to fill in (same rows and meals as the grid). */
  @RequirePermission(...ON_BEHALF_UNIT)
  @Get('units/:orgUnitId/import-template')
  async importTemplate(
    @Param('orgUnitId') orgUnitId: string,
    @Query(new ZodValidationPipe(GridQuerySchema)) q: z.infer<typeof GridQuerySchema>,
    @CurrentPrincipal() p: RequestPrincipal,
    @Res() res: Response,
  ) {
    const file = await this.imports.template(p.factoryId, orgUnitId, q.weekStart, q.canteenId);
    res
      .status(200)
      .setHeader('content-type', XLSX_MIME)
      .setHeader('content-disposition', `attachment; filename="dang-ky-${q.weekStart}.xlsx"`)
      .send(file);
  }

  /** Validates an uploaded week (preview) or, with `apply=true`, saves it all at once. */
  @RequirePermission(...ON_BEHALF_UNIT)
  @Post('units/:orgUnitId/import')
  @HttpCode(HttpStatus.OK)
  @UseInterceptors(FileInterceptor('file', { limits: { fileSize: MAX_UPLOAD_BYTES, files: 1 } }))
  importWeek(
    @Param('orgUnitId') orgUnitId: string,
    @Query(new ZodValidationPipe(ImportQuerySchema)) q: z.infer<typeof ImportQuerySchema>,
    @UploadedFile() file: { buffer: Buffer } | undefined,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    if (!file) throw invalid('File is required', [{ path: 'file', message: 'FILE_REQUIRED' }]);
    return this.imports.importWeek(p.employeeId, p.factoryId, orgUnitId, q, file.buffer);
  }

  /** BR-25: extra meals after the lock, with a reason. */
  @RequirePermission('registration.addExtra', { type: 'employeeParam', param: 'code' })
  @Post('employees/:code/extras')
  @HttpCode(HttpStatus.OK)
  addExtra(
    @Param('code') code: string,
    @Body(new ZodValidationPipe(ExtraMealSchema)) body: ExtraMeal,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.admin.addExtra(p.employeeId, p.factoryId, code, body);
  }
}
