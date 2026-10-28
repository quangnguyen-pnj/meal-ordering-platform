import {
  Body,
  Controller,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  Patch,
  Post,
  Res,
  UploadedFile,
  UseInterceptors,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { AssignDeptAdminSchema, OrgUnitUpdateSchema, type OrgUnitUpdate } from '@meal/shared';
import type { Response } from 'express';
import type { z } from 'zod';
import { invalid } from '../common/api-error';
import { ZodValidationPipe } from '../common/zod-validation.pipe';
import { CurrentPrincipal, RequirePermission } from '../rbac/decorators';
import type { RequestPrincipal } from '../rbac/rbac.types';
import { MappingImportService } from './mapping-import.service';
import { OrgSyncService } from './org-sync.service';
import { OrgService } from './org.service';

const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

/** The parts of a multer upload this controller reads (memory storage). */
interface UploadedXlsx {
  originalname: string;
  buffer: Buffer;
}
const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

/** /admin/org: meal-unit flags, mapping, dept admins, lists, Excel import/export (BR-60..BR-64). */
@Controller('admin/org')
export class OrgController {
  constructor(
    private readonly org: OrgService,
    private readonly imports: MappingImportService,
    private readonly sync: OrgSyncService,
  ) {}

  @RequirePermission('org.manage', { type: 'factory' })
  @Get('tree')
  async tree(@CurrentPrincipal() p: RequestPrincipal) {
    return { items: await this.org.tree(p.factoryId) };
  }

  @RequirePermission('org.manage', { type: 'orgUnitParam', param: 'id' })
  @Patch('units/:id')
  updateUnit(
    @Param('id') id: string,
    @Body(new ZodValidationPipe(OrgUnitUpdateSchema)) body: OrgUnitUpdate,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.org.updateUnit(p.employeeId, p.factoryId, id, body);
  }

  @RequirePermission('org.manage', { type: 'orgUnitParam', param: 'id' })
  @Post('units/:id/dept-admins')
  assignDeptAdmin(
    @Param('id') id: string,
    @Body(new ZodValidationPipe(AssignDeptAdminSchema)) body: z.infer<typeof AssignDeptAdminSchema>,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.org.assignDeptAdmin(p.employeeId, p.factoryId, id, body.employeeCode);
  }

  @RequirePermission('org.manage', { type: 'factory' })
  @Post('dept-admins/:assignmentId/revoke')
  @HttpCode(HttpStatus.OK)
  revokeDeptAdmin(
    @Param('assignmentId') assignmentId: string,
    @CurrentPrincipal() p: RequestPrincipal,
  ) {
    return this.org.revokeDeptAdmin(p.employeeId, p.factoryId, assignmentId);
  }

  @RequirePermission('org.manage', { type: 'factory' })
  @Get('unassigned')
  async unassigned(@CurrentPrincipal() p: RequestPrincipal) {
    return { items: await this.org.unassigned(p.factoryId) };
  }

  @RequirePermission('org.manage', { type: 'factory' })
  @Get('missing-cost-center')
  async missingCostCenter(@CurrentPrincipal() p: RequestPrincipal) {
    return { items: await this.org.missingCostCenter(p.factoryId) };
  }

  /** BR-64: export uses the import template. */
  @RequirePermission('org.manage', { type: 'factory' })
  @Get('mapping/export')
  async exportMapping(@CurrentPrincipal() p: RequestPrincipal, @Res() res: Response) {
    const buffer = await this.imports.exportWorkbook(p.factoryId);
    res
      .status(200)
      .setHeader('content-type', XLSX_MIME)
      .setHeader('content-disposition', 'attachment; filename="mapping-to-chuc.xlsx"')
      .send(buffer);
  }

  /** BR-64: upload validates every row; one invalid row rejects the file. Nothing is applied yet. */
  @RequirePermission('org.manage', { type: 'factory' })
  @Post('mapping/imports')
  @UseInterceptors(FileInterceptor('file', { limits: { fileSize: MAX_UPLOAD_BYTES, files: 1 } }))
  upload(@UploadedFile() file: UploadedXlsx | undefined, @CurrentPrincipal() p: RequestPrincipal) {
    if (!file) throw invalid('File is required', [{ path: 'file', message: 'FILE_REQUIRED' }]);
    return this.imports.upload(p.employeeId, p.factoryId, file.originalname, file.buffer);
  }

  @RequirePermission('org.manage', { type: 'factory' })
  @Get('mapping/imports/:id')
  importView(@Param('id') id: string, @CurrentPrincipal() p: RequestPrincipal) {
    return this.imports.view(id, p.factoryId);
  }

  /** BR-64: all-or-nothing apply of a previewed upload. */
  @RequirePermission('org.manage', { type: 'factory' })
  @Post('mapping/imports/:id/apply')
  @HttpCode(HttpStatus.OK)
  apply(@Param('id') id: string, @CurrentPrincipal() p: RequestPrincipal) {
    return this.imports.apply(p.employeeId, p.factoryId, id);
  }

  /** Plan section 5: manual trigger of the org + employee sync (also runs daily at 02:00). */
  @RequirePermission('sync.manage', { type: 'factory' })
  @Post('sync')
  @HttpCode(HttpStatus.OK)
  runSync(@CurrentPrincipal() p: RequestPrincipal) {
    return this.sync.runOrThrow(p.factoryId, p.employeeId);
  }
}
