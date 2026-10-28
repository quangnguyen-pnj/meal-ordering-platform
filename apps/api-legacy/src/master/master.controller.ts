import { Body, Controller, Get, Param, Patch, Post, Put } from '@nestjs/common';
import {
  CanteenInputSchema,
  CanteenUpdateSchema,
  CostCenterInputSchema,
  CostCenterUpdateSchema,
  FloorInputSchema,
  FloorUpdateSchema,
  SupplierUpdateSchema,
  type SupplierUpdate,
} from '@meal/shared';
import type { z } from 'zod';
import { ZodValidationPipe } from '../common/zod-validation.pipe';
import { CurrentPrincipal, RequirePermission } from '../rbac/decorators';
import type { RequestPrincipal } from '../rbac/rbac.types';
import { MasterService } from './master.service';

const ORG_MANAGE = ['org.manage', { type: 'factory' }] as const;

/** /admin/master: canteens, floors, cost centers (BR-62). HC admin and system admin. */
@Controller('admin/master')
export class MasterController {
  constructor(private readonly master: MasterService) {}

  @RequirePermission(...ORG_MANAGE)
  @Get('canteens')
  async canteens(@CurrentPrincipal() p: RequestPrincipal) {
    return { items: await this.master.list('canteen', p.factoryId) };
  }

  @RequirePermission(...ORG_MANAGE)
  @Post('canteens')
  createCanteen(
    @Body(new ZodValidationPipe(CanteenInputSchema)) body: z.infer<typeof CanteenInputSchema>,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.master.create('canteen', p.employeeId, p.factoryId, body);
  }

  @RequirePermission(...ORG_MANAGE)
  @Patch('canteens/:id')
  updateCanteen(
    @Param('id') id: string,
    @Body(new ZodValidationPipe(CanteenUpdateSchema)) body: z.infer<typeof CanteenUpdateSchema>,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.master.update('canteen', p.employeeId, p.factoryId, id, body);
  }

  @RequirePermission(...ORG_MANAGE)
  @Get('floors')
  async floors(@CurrentPrincipal() p: RequestPrincipal) {
    return { items: await this.master.list('floor', p.factoryId) };
  }

  @RequirePermission(...ORG_MANAGE)
  @Post('floors')
  createFloor(
    @Body(new ZodValidationPipe(FloorInputSchema)) body: z.infer<typeof FloorInputSchema>,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.master.create('floor', p.employeeId, p.factoryId, body);
  }

  @RequirePermission(...ORG_MANAGE)
  @Patch('floors/:id')
  updateFloor(
    @Param('id') id: string,
    @Body(new ZodValidationPipe(FloorUpdateSchema)) body: z.infer<typeof FloorUpdateSchema>,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.master.update('floor', p.employeeId, p.factoryId, id, body);
  }

  @RequirePermission(...ORG_MANAGE)
  @Get('cost-centers')
  async costCenters(@CurrentPrincipal() p: RequestPrincipal) {
    return { items: await this.master.list('costCenter', p.factoryId) };
  }

  @RequirePermission(...ORG_MANAGE)
  @Post('cost-centers')
  createCostCenter(
    @Body(new ZodValidationPipe(CostCenterInputSchema)) body: z.infer<typeof CostCenterInputSchema>,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.master.create('costCenter', p.employeeId, p.factoryId, body);
  }

  @RequirePermission(...ORG_MANAGE)
  @Patch('cost-centers/:id')
  updateCostCenter(
    @Param('id') id: string,
    @Body(new ZodValidationPipe(CostCenterUpdateSchema))
    body: z.infer<typeof CostCenterUpdateSchema>,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.master.update('costCenter', p.employeeId, p.factoryId, id, body);
  }
}

/** Caterer name and unit price, part of business settings (plan section 3). */
@Controller('admin/supplier')
export class SupplierController {
  constructor(private readonly master: MasterService) {}

  @RequirePermission('settings.manage', { type: 'factory' })
  @Get()
  get(@CurrentPrincipal() p: RequestPrincipal) {
    return this.master.supplier(p.factoryId);
  }

  @RequirePermission('settings.manage', { type: 'factory' })
  @Put()
  update(
    @Body(new ZodValidationPipe(SupplierUpdateSchema)) body: SupplierUpdate,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.master.updateSupplier(p.employeeId, p.factoryId, body);
  }
}
