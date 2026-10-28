import {
  Body,
  Controller,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  Patch,
  Post,
  Put,
  Query,
  Res,
  UploadedFile,
  UseInterceptors,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import {
  DessertInputSchema,
  DessertUpdateSchema,
  DishInputSchema,
  DishTypeInputSchema,
  DishTypeUpdateSchema,
  DishUpdateSchema,
  type DessertInput,
  type DessertUpdate,
  type DishInput,
  type DishTypeInput,
  type DishTypeUpdate,
  type DishUpdate,
} from '@meal/shared';
import type { Response } from 'express';
import { z } from 'zod';
import { invalid } from '../common/api-error';
import { ZodValidationPipe } from '../common/zod-validation.pipe';
import { CurrentPrincipal, RequirePermission } from '../rbac/decorators';
import type { RequestPrincipal } from '../rbac/rbac.types';
import { DishesService } from './dishes.service';

const ListQuerySchema = z.object({
  canteenId: z.string().trim().min(1),
  includeArchived: z
    .enum(['true', 'false'])
    .optional()
    .transform((v) => v === 'true'),
});
type ListQuery = z.infer<typeof ListQuerySchema>;

const ImportQuerySchema = z.object({
  canteenId: z.string().trim().min(1),
  apply: z
    .enum(['true', 'false'])
    .optional()
    .transform((v) => v === 'true'),
});
type ImportQuery = z.infer<typeof ImportQuerySchema>;

const MANAGE = ['dishes.manage', { type: 'factory' }] as const;
const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

/**
 * /admin/dishes: each canteen's main dishes and desserts (BR-10), the dish types they use, and the
 * Excel import of a library. HC admin only.
 */
@Controller('admin')
export class DishesController {
  constructor(private readonly dishes: DishesService) {}

  @RequirePermission(...MANAGE)
  @Get('dishes')
  async listDishes(
    @Query(new ZodValidationPipe(ListQuerySchema)) q: ListQuery,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return { items: await this.dishes.listDishes(p.factoryId, q.canteenId, q.includeArchived) };
  }

  @RequirePermission(...MANAGE)
  @Post('dishes')
  createDish(
    @Body(new ZodValidationPipe(DishInputSchema)) body: DishInput,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.dishes.createDish(p.employeeId, p.factoryId, body);
  }

  @RequirePermission(...MANAGE)
  @Put('dishes/:id')
  updateDish(
    @Param('id') id: string,
    @Body(new ZodValidationPipe(DishUpdateSchema)) body: DishUpdate,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.dishes.updateDish(p.employeeId, p.factoryId, id, body);
  }

  @RequirePermission(...MANAGE)
  @Post('dishes/:id/archive')
  @HttpCode(HttpStatus.OK)
  archiveDish(@Param('id') id: string, @CurrentPrincipal() p: RequestPrincipal) {
    return this.dishes.setArchived('dish', p.employeeId, p.factoryId, id, true);
  }

  @RequirePermission(...MANAGE)
  @Post('dishes/:id/restore')
  @HttpCode(HttpStatus.OK)
  restoreDish(@Param('id') id: string, @CurrentPrincipal() p: RequestPrincipal) {
    return this.dishes.setArchived('dish', p.employeeId, p.factoryId, id, false);
  }

  @RequirePermission(...MANAGE)
  @Get('desserts')
  async listDesserts(
    @Query(new ZodValidationPipe(ListQuerySchema)) q: ListQuery,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return {
      items: await this.dishes.listDesserts(p.factoryId, q.canteenId, q.includeArchived),
    };
  }

  @RequirePermission(...MANAGE)
  @Post('desserts')
  createDessert(
    @Body(new ZodValidationPipe(DessertInputSchema)) body: DessertInput,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.dishes.createDessert(p.employeeId, p.factoryId, body);
  }

  @RequirePermission(...MANAGE)
  @Put('desserts/:id')
  updateDessert(
    @Param('id') id: string,
    @Body(new ZodValidationPipe(DessertUpdateSchema)) body: DessertUpdate,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.dishes.updateDessert(p.employeeId, p.factoryId, id, body);
  }

  @RequirePermission(...MANAGE)
  @Post('desserts/:id/archive')
  @HttpCode(HttpStatus.OK)
  archiveDessert(@Param('id') id: string, @CurrentPrincipal() p: RequestPrincipal) {
    return this.dishes.setArchived('dessert', p.employeeId, p.factoryId, id, true);
  }

  @RequirePermission(...MANAGE)
  @Post('desserts/:id/restore')
  @HttpCode(HttpStatus.OK)
  restoreDessert(@Param('id') id: string, @CurrentPrincipal() p: RequestPrincipal) {
    return this.dishes.setArchived('dessert', p.employeeId, p.factoryId, id, false);
  }

  @RequirePermission(...MANAGE)
  @Get('dish-types')
  async listTypes(@CurrentPrincipal() p: RequestPrincipal) {
    return { items: await this.dishes.listTypes(p.factoryId) };
  }

  @RequirePermission(...MANAGE)
  @Post('dish-types')
  createType(
    @Body(new ZodValidationPipe(DishTypeInputSchema)) body: DishTypeInput,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.dishes.createType(p.employeeId, p.factoryId, body);
  }

  @RequirePermission(...MANAGE)
  @Patch('dish-types/:id')
  updateType(
    @Param('id') id: string,
    @Body(new ZodValidationPipe(DishTypeUpdateSchema)) body: DishTypeUpdate,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.dishes.updateType(p.employeeId, p.factoryId, id, body);
  }

  @RequirePermission(...MANAGE)
  @Get('dish-library/template')
  async template(@CurrentPrincipal() p: RequestPrincipal, @Res() res: Response) {
    const buffer = await this.dishes.templateWorkbook(p.factoryId);
    res
      .status(200)
      .setHeader('content-type', XLSX_MIME)
      .setHeader('content-disposition', 'attachment; filename="mau-thu-vien-mon.xlsx"')
      .send(buffer);
  }

  /** Validates the file (preview) or, with `apply=true`, adds its new entries all at once. */
  @RequirePermission(...MANAGE)
  @Post('dish-library/import')
  @HttpCode(HttpStatus.OK)
  @UseInterceptors(FileInterceptor('file', { limits: { fileSize: MAX_UPLOAD_BYTES, files: 1 } }))
  importLibrary(
    @Query(new ZodValidationPipe(ImportQuerySchema)) q: ImportQuery,
    @UploadedFile() file: { buffer: Buffer } | undefined,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    if (!file) throw invalid('File is required', [{ path: 'file', message: 'FILE_REQUIRED' }]);
    return this.dishes.importLibrary(p.employeeId, p.factoryId, q.canteenId, file.buffer, q.apply);
  }
}
