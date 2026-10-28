import { Injectable } from '@nestjs/common';
import type { Prisma } from '@prisma/client';
import ExcelJS from 'exceljs';
import {
  validateMappingRows,
  type MappingImportView,
  type MappingRow,
  type MappingRowError,
  type RawMappingRow,
} from '@meal/shared';
import { AuditService } from '../audit/audit.service';
import { ClockService } from '../clock/clock.service';
import { invalid, invalidState, notFound } from '../common/api-error';
import { PrismaService } from '../prisma/prisma.service';

/** BR-64 template: one sheet, these columns in this order; row 1 is the header. */
export const MAPPING_TEMPLATE_HEADER = [
  'Loại (ORG/EMPLOYEE)',
  'Mã đơn vị / mã nhân viên',
  'Tên (chỉ để đọc)',
  'Mã tầng',
  'Mã nhà ăn',
  'Mã trung tâm chi phí',
] as const;
const SHEET_NAME = 'Mapping';
const MAX_ROWS = 20_000;

type CodeTriple = {
  floorCode: string | null;
  canteenCode: string | null;
  costCenterCode: string | null;
};

const cellText = (row: ExcelJS.Row, col: number): string => row.getCell(col).text?.trim() ?? '';

@Injectable()
export class MappingImportService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
    private readonly clock: ClockService,
  ) {}

  /** BR-64: export uses the same template as import, filled with the current own mapping. */
  async exportWorkbook(factoryId: string): Promise<Buffer> {
    const [units, employees, floors, canteens, costCenters] = await Promise.all([
      this.prisma.orgUnit.findMany({
        where: { factoryId },
        orderBy: { path: 'asc' },
        include: { floor: true, canteen: true, costCenter: true },
      }),
      this.prisma.employee.findMany({
        where: { orgUnit: { factoryId }, floorOverrideId: { not: null } },
        orderBy: { employeeCode: 'asc' },
        include: { floorOverride: true },
      }),
      this.prisma.floor.findMany({ where: { factoryId }, orderBy: { sortOrder: 'asc' } }),
      this.prisma.canteen.findMany({ where: { factoryId }, orderBy: { code: 'asc' } }),
      this.prisma.costCenter.findMany({ where: { factoryId }, orderBy: { code: 'asc' } }),
    ]);

    const wb = new ExcelJS.Workbook();
    const sheet = wb.addWorksheet(SHEET_NAME);
    sheet.addRow([...MAPPING_TEMPLATE_HEADER]);
    sheet.getRow(1).font = { bold: true };
    sheet.columns.forEach((c, i) => (c.width = [20, 26, 40, 12, 14, 24][i]));
    for (const u of units) {
      sheet.addRow([
        'ORG',
        u.dmpCode,
        `${'  '.repeat(u.level - 1)}${u.name}`,
        u.floor?.code ?? '',
        u.canteen?.code ?? '',
        u.costCenter?.code ?? '',
      ]);
    }
    for (const e of employees) {
      sheet.addRow(['EMPLOYEE', e.employeeCode, e.name, e.floorOverride?.code ?? '', '', '']);
    }

    const lists = wb.addWorksheet('Danh mục');
    lists.addRow(['Loại', 'Mã', 'Tên', 'Đang dùng']);
    lists.getRow(1).font = { bold: true };
    for (const f of floors) lists.addRow(['Tầng', f.code, f.name, f.active ? 'Có' : 'Không']);
    for (const c of canteens) lists.addRow(['Nhà ăn', c.code, c.name, c.active ? 'Có' : 'Không']);
    for (const c of costCenters)
      lists.addRow(['Trung tâm chi phí', c.code, c.name, c.active ? 'Có' : 'Không']);

    return Buffer.from(await wb.xlsx.writeBuffer());
  }

  /** Reads the first sheet. Throws a 400 when the file is not a readable template. */
  async parse(buffer: Buffer): Promise<RawMappingRow[]> {
    const wb = new ExcelJS.Workbook();
    try {
      await wb.xlsx.load(buffer as unknown as ArrayBuffer);
    } catch {
      throw invalid('Not a readable .xlsx file', [{ path: 'file', message: 'FILE_UNREADABLE' }]);
    }
    const sheet = wb.worksheets[0];
    if (!sheet || cellText(sheet.getRow(1), 1) !== MAPPING_TEMPLATE_HEADER[0]) {
      throw invalid('The file does not use the mapping template', [
        { path: 'file', message: 'TEMPLATE_MISMATCH' },
      ]);
    }
    const rows: RawMappingRow[] = [];
    sheet.eachRow({ includeEmpty: false }, (row, rowNumber) => {
      if (rowNumber === 1) return;
      const cells = [1, 2, 4, 5, 6].map((c) => cellText(row, c));
      if (cells.every((c) => c === '')) return;
      rows.push({
        rowNumber,
        type: cells[0]!,
        code: cells[1]!,
        floorCode: cells[2]!,
        canteenCode: cells[3]!,
        costCenterCode: cells[4]!,
      });
    });
    if (rows.length === 0) {
      throw invalid('The file has no rows', [{ path: 'file', message: 'EMPTY_FILE' }]);
    }
    if (rows.length > MAX_ROWS) {
      throw invalid('Too many rows', [{ path: 'file', message: 'TOO_MANY_ROWS' }]);
    }
    return rows;
  }

  /**
   * BR-64: validates every row and stores the upload. One invalid row rejects the whole file
   * (status REJECTED, nothing applied); otherwise the upload waits as a PREVIEW.
   */
  async upload(
    actorId: string,
    factoryId: string,
    fileName: string,
    buffer: Buffer,
  ): Promise<MappingImportView> {
    const raw = await this.parse(buffer);
    const lookups = await this.lookups(factoryId);
    const result = validateMappingRows(raw, {
      orgCodes: new Set(lookups.units.keys()),
      employeeCodes: new Set(lookups.employees.keys()),
      floorCodes: lookups.activeCodes.floor,
      canteenCodes: lookups.activeCodes.canteen,
      costCenterCodes: lookups.activeCodes.costCenter,
    });
    const errors: MappingRowError[] = result.ok ? [] : result.errors;
    const row = await this.prisma.mappingImport.create({
      data: {
        factoryId,
        fileName: fileName.slice(0, 200),
        uploadedById: actorId,
        status: result.ok ? 'PREVIEW' : 'REJECTED',
        rowCount: raw.length,
        errorCount: errors.length,
        rows: (result.ok ? result.rows : []) as unknown as Prisma.InputJsonValue,
        errors: errors as unknown as Prisma.InputJsonValue,
        createdAt: this.clock.now(),
      },
    });
    return this.view(row.id, factoryId);
  }

  async view(importId: string, factoryId: string): Promise<MappingImportView> {
    const row = await this.prisma.mappingImport.findUnique({ where: { id: importId } });
    if (!row || row.factoryId !== factoryId) throw notFound('Import not found');
    const rows = row.rows as unknown as MappingRow[];
    const changes = row.status === 'PREVIEW' ? this.diff(rows, await this.lookups(factoryId)) : [];
    return {
      id: row.id,
      fileName: row.fileName,
      status: row.status,
      rowCount: row.rowCount,
      errorCount: row.errorCount,
      errors: row.errors as unknown as MappingRowError[],
      changes,
      appliedAt: row.appliedAt?.toISOString() ?? null,
    };
  }

  /**
   * BR-64: applies a PREVIEW all at once in one transaction, re-validating against the current
   * master data first. Every changed target is audited. Snapshot rows (forecast, final,
   * adjustment lines) keep the mapping they were written with: they are never touched here.
   */
  async apply(actorId: string, factoryId: string, importId: string): Promise<MappingImportView> {
    await this.prisma.$transaction(
      async (tx) => {
        // Lock the import row so two clicks cannot apply it twice.
        const locked = await tx.$queryRaw<Array<{ status: string }>>`
          SELECT "status"::text AS status FROM "MappingImport"
          WHERE "id" = ${importId} AND "factoryId" = ${factoryId} FOR UPDATE`;
        if (locked.length === 0) throw notFound('Import not found');
        if (locked[0]!.status !== 'PREVIEW') throw invalidState('Import is not a pending preview');

        const imp = await tx.mappingImport.findUniqueOrThrow({ where: { id: importId } });
        const rows = imp.rows as unknown as MappingRow[];
        const lookups = await this.lookups(factoryId, tx);
        const recheck = validateMappingRows(
          rows.map((r) => ({
            rowNumber: r.rowNumber,
            type: r.type,
            code: r.code,
            floorCode: r.floorCode ?? '',
            canteenCode: r.canteenCode ?? '',
            costCenterCode: r.costCenterCode ?? '',
          })),
          {
            orgCodes: new Set(lookups.units.keys()),
            employeeCodes: new Set(lookups.employees.keys()),
            floorCodes: lookups.activeCodes.floor,
            canteenCodes: lookups.activeCodes.canteen,
            costCenterCodes: lookups.activeCodes.costCenter,
          },
        );
        if (!recheck.ok) {
          throw invalidState('Master data changed since the preview; upload the file again', {
            errors: recheck.errors,
          });
        }

        const changes = this.diff(rows, lookups);
        for (const c of changes) {
          if (c.type === 'ORG') {
            const unit = lookups.units.get(c.code)!;
            await tx.orgUnit.update({
              where: { id: unit.id },
              data: {
                floorId: c.after.floorCode ? lookups.ids.floor.get(c.after.floorCode)! : null,
                canteenId: c.after.canteenCode
                  ? lookups.ids.canteen.get(c.after.canteenCode)!
                  : null,
                costCenterId: c.after.costCenterCode
                  ? lookups.ids.costCenter.get(c.after.costCenterCode)!
                  : null,
              },
            });
            await this.audit.log(
              {
                actorId,
                action: 'orgUnit.mapping.updated',
                entityType: 'OrgUnit',
                entityId: unit.id,
                before: c.before,
                after: c.after,
                reason: `Import ${imp.fileName} (${imp.id}), row ${c.rowNumber}`,
              },
              tx,
            );
          } else {
            const employee = lookups.employees.get(c.code)!;
            await tx.employee.update({
              where: { id: employee.id },
              data: {
                floorOverrideId: c.after.floorCode
                  ? lookups.ids.floor.get(c.after.floorCode)!
                  : null,
              },
            });
            await this.audit.log(
              {
                actorId,
                action: 'employee.floorOverride.set',
                entityType: 'Employee',
                entityId: employee.id,
                before: { floorCode: c.before.floorCode },
                after: { floorCode: c.after.floorCode },
                reason: `Import ${imp.fileName} (${imp.id}), row ${c.rowNumber}`,
              },
              tx,
            );
          }
        }
        await tx.mappingImport.update({
          where: { id: importId },
          data: { status: 'APPLIED', appliedAt: this.clock.now() },
        });
        await this.audit.log(
          {
            actorId,
            action: 'mapping.import.applied',
            entityType: 'MappingImport',
            entityId: importId,
            after: { fileName: imp.fileName, rowCount: imp.rowCount, changed: changes.length },
          },
          tx,
        );
      },
      { timeout: 60_000 },
    );
    return this.view(importId, factoryId);
  }

  private diff(rows: MappingRow[], lookups: Lookups): MappingImportView['changes'] {
    const changes: MappingImportView['changes'] = [];
    for (const r of rows) {
      const after: CodeTriple = {
        floorCode: r.floorCode,
        canteenCode: r.canteenCode,
        costCenterCode: r.costCenterCode,
      };
      let before: CodeTriple;
      let name: string;
      if (r.type === 'ORG') {
        const unit = lookups.units.get(r.code);
        if (!unit) continue;
        name = unit.name;
        before = {
          floorCode: unit.floorCode,
          canteenCode: unit.canteenCode,
          costCenterCode: unit.costCenterCode,
        };
      } else {
        const employee = lookups.employees.get(r.code);
        if (!employee) continue;
        name = employee.name;
        before = { floorCode: employee.floorCode, canteenCode: null, costCenterCode: null };
      }
      if (
        before.floorCode !== after.floorCode ||
        before.canteenCode !== after.canteenCode ||
        before.costCenterCode !== after.costCenterCode
      ) {
        changes.push({ rowNumber: r.rowNumber, type: r.type, code: r.code, name, before, after });
      }
    }
    return changes;
  }

  private async lookups(
    factoryId: string,
    db: Prisma.TransactionClient | PrismaService = this.prisma,
  ): Promise<Lookups> {
    const [units, employees, floors, canteens, costCenters] = await Promise.all([
      db.orgUnit.findMany({
        where: { factoryId },
        select: {
          id: true,
          dmpCode: true,
          name: true,
          floor: { select: { code: true } },
          canteen: { select: { code: true } },
          costCenter: { select: { code: true } },
        },
      }),
      db.employee.findMany({
        where: { orgUnit: { factoryId } },
        select: {
          id: true,
          employeeCode: true,
          name: true,
          floorOverride: { select: { code: true } },
        },
      }),
      db.floor.findMany({ where: { factoryId }, select: { id: true, code: true, active: true } }),
      db.canteen.findMany({ where: { factoryId }, select: { id: true, code: true, active: true } }),
      db.costCenter.findMany({
        where: { factoryId },
        select: { id: true, code: true, active: true },
      }),
    ]);
    const active = (rows: Array<{ code: string; active: boolean }>) =>
      new Set(rows.filter((r) => r.active).map((r) => r.code));
    const ids = (rows: Array<{ id: string; code: string }>) =>
      new Map(rows.map((r) => [r.code, r.id]));
    return {
      units: new Map(
        units.map((u) => [
          u.dmpCode,
          {
            id: u.id,
            name: u.name,
            floorCode: u.floor?.code ?? null,
            canteenCode: u.canteen?.code ?? null,
            costCenterCode: u.costCenter?.code ?? null,
          },
        ]),
      ),
      employees: new Map(
        employees.map((e) => [
          e.employeeCode,
          { id: e.id, name: e.name, floorCode: e.floorOverride?.code ?? null },
        ]),
      ),
      activeCodes: {
        floor: active(floors),
        canteen: active(canteens),
        costCenter: active(costCenters),
      },
      ids: { floor: ids(floors), canteen: ids(canteens), costCenter: ids(costCenters) },
    };
  }
}

interface Lookups {
  units: Map<string, { id: string; name: string } & CodeTriple>;
  employees: Map<string, { id: string; name: string; floorCode: string | null }>;
  activeCodes: { floor: Set<string>; canteen: Set<string>; costCenter: Set<string> };
  ids: {
    floor: Map<string, string>;
    canteen: Map<string, string>;
    costCenter: Map<string, string>;
  };
}
