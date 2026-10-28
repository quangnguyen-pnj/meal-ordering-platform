import { Injectable } from '@nestjs/common';
import ExcelJS from 'exceljs';
import {
  isoWeekdayOf,
  registrationColumnKey,
  validateRegistrationImport,
  type RawRegistrationImportRow,
  type RegistrationGridView,
  type RegistrationImportView,
} from '@meal/shared';
import { invalid, invalidState } from '../common/api-error';
import { AdminRegistrationsService } from './admin-registrations.service';

/** Row 1 is for people, row 2 (hidden) holds the column keys; data starts on row 3. */
const CODE_HEADER = 'Mã NV';
const KEY_MARKER = '#';
const FIRST_DATA_ROW = 3;
const FIXED_COLUMNS = 3;
const MAX_ROWS = 2_000;
const WEEKDAY = ['', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN'];

const cellText = (row: ExcelJS.Row, col: number): string => row.getCell(col).text?.trim() ?? '';
const ddmm = (date: string) => `${date.slice(8, 10)}/${date.slice(5, 7)}`;

/**
 * Excel import of a unit's week (product owner, 2026-10-23). The template is the bulk grid as a
 * file: one row per employee, one column per meal of every day still open for on-behalf entry,
 * filled with the current quantities. Uploading validates the whole file (shared
 * `validateRegistrationImport`), and applying saves every changed day in one transaction through
 * the grid's own save, so the grid's rules and audit apply unchanged (BR-20, BR-21, BR-70).
 */
@Injectable()
export class RegistrationImportService {
  constructor(private readonly admin: AdminRegistrationsService) {}

  async template(
    factoryId: string,
    orgUnitId: string,
    weekStart: string,
    canteenId: string | undefined,
  ): Promise<Buffer> {
    const grid = await this.publishedGrid(factoryId, orgUnitId, weekStart, canteenId);
    const days = grid.days.filter((d) => d.window === 'ON_BEHALF');
    const columns = days.flatMap((d) =>
      d.items.map((i) => ({
        key: registrationColumnKey(d.date, i.id),
        label: `${WEEKDAY[isoWeekdayOf(d.date)]} ${ddmm(d.date)} · ${i.displayName}`,
        date: d.date,
        itemId: i.id,
      })),
    );

    const wb = new ExcelJS.Workbook();
    const sheet = wb.addWorksheet('Đăng ký');
    sheet.addRow([CODE_HEADER, 'Họ tên', 'Đơn vị', ...columns.map((c) => c.label)]);
    sheet.addRow([KEY_MARKER, '', '', ...columns.map((c) => c.key)]);
    sheet.getRow(1).font = { bold: true };
    sheet.getRow(1).alignment = { wrapText: true, vertical: 'top' };
    sheet.getRow(2).hidden = true;
    sheet.views = [{ state: 'frozen', xSplit: 2, ySplit: 2 }];
    sheet.getColumn(1).width = 14;
    sheet.getColumn(2).width = 28;
    sheet.getColumn(3).width = 36;
    columns.forEach((_, i) => (sheet.getColumn(FIXED_COLUMNS + 1 + i).width = 18));
    for (const row of grid.rows) {
      sheet.addRow([
        row.employeeCode,
        row.name,
        row.orgPath,
        ...columns.map((c) => row.cells[c.date]?.quantities[c.itemId] || null),
      ]);
    }
    const info = wb.addWorksheet('Hướng dẫn');
    for (const line of [
      `Đơn vị: ${grid.orgUnit.pathName}`,
      `Nhà ăn: ${grid.canteenName ?? ''}`,
      `Tuần bắt đầu: ${weekStart}`,
      'Nhập số suất (số nguyên, để trống = không ăn). Không thêm, xóa hay đổi thứ tự cột.',
      'Chỉ có các ngày còn đăng ký hộ được (trước giờ khóa của ngày).',
    ]) {
      info.addRow([line]);
    }
    return Buffer.from(await wb.xlsx.writeBuffer());
  }

  async importWeek(
    actorId: string,
    factoryId: string,
    orgUnitId: string,
    q: { weekStart: string; canteenId?: string; apply: boolean },
    buffer: Buffer,
  ): Promise<RegistrationImportView> {
    const { columns, rows } = await this.parse(buffer);
    const grid = await this.publishedGrid(factoryId, orgUnitId, q.weekStart, q.canteenId);
    const editable = new Map(
      grid.days
        .filter((d) => d.window === 'ON_BEHALF')
        .map((d) => [d.date, new Set(d.items.map((i) => i.id))]),
    );
    const current = new Map(
      grid.rows.map((r) => [
        r.employeeCode,
        Object.fromEntries(Object.entries(r.cells).map(([date, c]) => [date, c.quantities])),
      ]),
    );
    const result = validateRegistrationImport({ columns, rows, editable, current });
    if (!result.ok) {
      return {
        status: 'REJECTED',
        rowCount: rows.length,
        errors: result.errors,
        changedDays: 0,
        changedEmployees: 0,
      };
    }
    const changedEmployees = new Set(result.changes.map((c) => c.employeeCode)).size;
    if (q.apply && result.changes.length > 0) {
      const byDate = new Map<
        string,
        Array<{ employeeCode: string; lines: (typeof result.changes)[number]['lines'] }>
      >();
      for (const c of result.changes) {
        const list = byDate.get(c.date) ?? [];
        list.push({ employeeCode: c.employeeCode, lines: c.lines });
        byDate.set(c.date, list);
      }
      await this.admin.saveGridDays(
        actorId,
        factoryId,
        orgUnitId,
        grid.canteenId!,
        [...byDate.entries()].sort().map(([date, rows]) => ({ date, rows })),
      );
    }
    return {
      status: q.apply ? 'APPLIED' : 'PREVIEW',
      rowCount: rows.length,
      errors: [],
      changedDays: result.changes.length,
      changedEmployees,
    };
  }

  private async publishedGrid(
    factoryId: string,
    orgUnitId: string,
    weekStart: string,
    canteenId: string | undefined,
  ): Promise<RegistrationGridView> {
    const grid = await this.admin.grid(factoryId, orgUnitId, weekStart, canteenId);
    if (grid.state !== 'PUBLISHED' || !grid.canteenId) {
      throw invalidState('No published menu for this unit and week (BR-02)', {
        state: grid.state,
      });
    }
    return grid;
  }

  private async parse(
    buffer: Buffer,
  ): Promise<{ columns: string[]; rows: RawRegistrationImportRow[] }> {
    const wb = new ExcelJS.Workbook();
    try {
      await wb.xlsx.load(buffer as unknown as ArrayBuffer);
    } catch {
      throw invalid('Not a readable .xlsx file', [{ path: 'file', message: 'FILE_UNREADABLE' }]);
    }
    const sheet = wb.worksheets[0];
    const keyRow = sheet?.getRow(2);
    if (!sheet || cellText(sheet.getRow(1), 1) !== CODE_HEADER || !keyRow) {
      throw invalid('The file does not use the registration template', [
        { path: 'file', message: 'TEMPLATE_MISMATCH' },
      ]);
    }
    if (cellText(keyRow, 1) !== KEY_MARKER) {
      throw invalid('The file does not use the registration template', [
        { path: 'file', message: 'TEMPLATE_MISMATCH' },
      ]);
    }
    const columns: Array<{ col: number; key: string }> = [];
    for (let col = FIXED_COLUMNS + 1; col <= keyRow.cellCount; col++) {
      const key = cellText(keyRow, col);
      if (key) columns.push({ col, key });
    }
    const rows: RawRegistrationImportRow[] = [];
    sheet.eachRow({ includeEmpty: false }, (row, rowNumber) => {
      if (rowNumber < FIRST_DATA_ROW) return;
      const employeeCode = cellText(row, 1);
      const cells = Object.fromEntries(columns.map((c) => [c.key, cellText(row, c.col)]));
      if (!employeeCode && Object.values(cells).every((v) => v === '')) return;
      rows.push({ rowNumber, employeeCode, cells });
    });
    if (rows.length > MAX_ROWS) {
      throw invalid('Too many rows', [{ path: 'file', message: 'TOO_MANY_ROWS' }]);
    }
    return { columns: columns.map((c) => c.key), rows };
  }
}
