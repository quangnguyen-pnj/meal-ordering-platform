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
} from '@nestjs/common';
import { FloorOverrideSchema } from '@meal/shared';
import { z } from 'zod';
import { PinAuthService } from '../auth/pin-auth.service';
import { ZodValidationPipe } from '../common/zod-validation.pipe';
import { CurrentPrincipal, RequirePermission } from '../rbac/decorators';
import type { AuthedRequest, RequestPrincipal } from '../rbac/rbac.types';
import { EmployeesService } from './employees.service';

const ListQuerySchema = z.object({
  q: z.string().trim().max(64).optional(),
  orgUnitId: z.string().min(1).optional(),
  floorId: z.string().min(1).optional(),
  canteenId: z.string().min(1).optional(),
  costCenterId: z.string().min(1).optional(),
  mealStatus: z.enum(['OK', 'NO_MEAL_UNIT', 'INVALID_MEAL_UNIT']).optional(),
  status: z.enum(['ACTIVE', 'LEFT']).optional(),
  offset: z.coerce.number().int().min(0).default(0),
  limit: z.coerce.number().int().min(1).max(100).default(50),
});

const ResetPinBodySchema = z.object({ reason: z.string().trim().max(500).optional() }).default({});

@Controller('employees')
export class EmployeesController {
  constructor(
    private readonly employees: EmployeesService,
    private readonly pinAuth: PinAuthService,
  ) {}

  /** /admin/employees finder. Gated by the closest matrix row ("Reset PIN": units / factory / system). */
  @RequirePermission('pin.reset', { type: 'list' })
  @Get()
  list(
    @Query(new ZodValidationPipe(ListQuerySchema)) query: z.infer<typeof ListQuerySchema>,
    @Req() req: AuthedRequest,
    @CurrentPrincipal() principal: RequestPrincipal,
  ) {
    const scope = req.listScope ?? { kind: 'none' as const };
    return this.employees.list(scope, principal.factoryId, query);
  }

  /** Khối / Trung tâm / Phòng ban filter options for the finder, within the caller's scope. */
  @RequirePermission('pin.reset', { type: 'list' })
  @Get('org-units')
  async orgUnits(@Req() req: AuthedRequest, @CurrentPrincipal() principal: RequestPrincipal) {
    const scope = req.listScope ?? { kind: 'none' as const };
    return { items: await this.employees.orgUnits(scope, principal.factoryId) };
  }

  @RequirePermission('pin.reset', { type: 'employeeParam', param: 'code' })
  @Post(':code/pin/reset')
  @HttpCode(HttpStatus.OK)
  resetPin(
    @Param('code') code: string,
    @Body(new ZodValidationPipe(ResetPinBodySchema)) body: z.infer<typeof ResetPinBodySchema>,
    @CurrentPrincipal() principal: RequestPrincipal,
  ) {
    return this.pinAuth.resetPin(principal.employeeId, code, body.reason);
  }

  /** BR-63: pick-up floor of one employee; part of "manage ... mapping" (HC admin). */
  @RequirePermission('org.manage', { type: 'employeeParam', param: 'code' })
  @Put(':code/floor-override')
  setFloorOverride(
    @Param('code') code: string,
    @Body(new ZodValidationPipe(FloorOverrideSchema))
    body: z.infer<typeof FloorOverrideSchema>,
    @CurrentPrincipal() principal: RequestPrincipal,
  ) {
    return this.employees.setFloorOverride(
      principal.employeeId,
      principal.factoryId,
      code,
      body.floorId,
      body.reason,
    );
  }
}
