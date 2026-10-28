import { Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import ExcelJS from 'exceljs';
import {
  libraryDishKey,
  libraryNameKey,
  mealDisplayName,
  validateLibraryRows,
  type DessertInput,
  type DessertUpdate,
  type DessertView,
  type DishInput,
  type DishTypeInput,
  type DishTypeUpdate,
  type DishTypeView,
  type DishUpdate,
  type DishView,
  type LibraryImportView,
  type RawLibraryRow,
} from '@meal/shared';
import { AuditService } from '../audit/audit.service';
import { ClockService } from '../clock/clock.service';
import { conflict, invalid, invalidState, notFound } from '../common/api-error';
import { PrismaService } from '../prisma/prisma.service';

type Library = 'dish' | 'dessert';
type Tx = Prisma.TransactionClient;

/** Library import template: one sheet, these columns in this order; row 1 is the header. */
export const LIBRARY_TEMPLATE_HEADER = [
  'Nhóm (Món chính / Tráng miệng)',
  'Mã loại món (món chính)',
  'Tên món',
] as const;
const MAX_IMPORT_ROWS = 2_000;
const LIBRARY_KIND_LABELS = { main: 'Món chính', dessert: 'Tráng miệng' } as const;
const DISH_TYPE_SHEET = 'Loại món';

/**
 * Turns the "Nhóm" and "Mã loại món" cells of every importable row into drop-down lists, so the
 * user picks a value instead of typing it. The type codes come from the second sheet (codes from
 * row 2 down). A blank type code stays allowed because desserts have none.
 */
function addLibraryDropdowns(sheet: ExcelJS.Worksheet, dishTypeCount: number): void {
  const kindList: ExcelJS.DataValidation = {
    type: 'list',
    allowBlank: true,
    formulae: [`"${Object.values(LIBRARY_KIND_LABELS).join(',')}"`],
    showErrorMessage: true,
    errorTitle: 'Nhóm không hợp lệ',
    error: 'Chọn "Món chính" hoặc "Tráng miệng" trong danh sách.',
  };
  const typeCodeList: ExcelJS.DataValidation = {
    type: 'list',
    allowBlank: true,
    formulae: [`'${DISH_TYPE_SHEET}'!$A$2:$A$${dishTypeCount + 1}`],
    showErrorMessage: true,
    errorTitle: 'Mã loại món không hợp lệ',
    error: `Chọn một mã trong danh sách (xem sheet "${DISH_TYPE_SHEET}"). Món tráng miệng để trống.`,
  };
  for (let rowNumber = 2; rowNumber <= MAX_IMPORT_ROWS + 1; rowNumber += 1) {
    const row = sheet.getRow(rowNumber);
    row.getCell(1).dataValidation = kindList;
    if (dishTypeCount > 0) row.getCell(2).dataValidation = typeCodeList;
  }
}

const cellText = (row: ExcelJS.Row, col: number): string => row.getCell(col).text?.trim() ?? '';

const isUniqueViolation = (error: unknown) =>
  error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002';

/** The "added twice" bug: the same name (any case or spacing) in one canteen's library. */
const duplicate = (what: 'dish' | 'dessert', existingId: string, archived: boolean) =>
  conflict(`This ${what} is already in the library`, [
    { path: 'name', message: archived ? 'DUPLICATE_ARCHIVED' : 'DUPLICATE', id: existingId },
  ]);

/**
 * BR-10: main-dish and dessert libraries, one per canteen, reused across weeks; and the dish types
 * they use (master data since 2026-10-23). Retired entries are archived and can be restored; they
 * are never deleted. Archived entries cannot be put on a new menu.
 */
@Injectable()
export class DishesService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
    private readonly clock: ClockService,
  ) {}

  // --- Dish types -------------------------------------------------------------------------

  async listTypes(factoryId: string): Promise<DishTypeView[]> {
    const rows = await this.prisma.dishType.findMany({
      where: { factoryId },
      orderBy: [{ sortOrder: 'asc' }, { name: 'asc' }],
      include: { _count: { select: { dishes: true } } },
    });
    return rows.map((r) => ({
      id: r.id,
      code: r.code,
      name: r.name,
      color: r.color,
      sortOrder: r.sortOrder,
      minPerDay: r.minPerDay,
      maxPerDay: r.maxPerDay,
      active: r.active,
      dishCount: r._count.dishes,
    }));
  }

  async createType(actorId: string, factoryId: string, input: DishTypeInput) {
    try {
      return await this.prisma.$transaction(async (tx) => {
        const row = await tx.dishType.create({
          data: { factoryId, ...input, code: input.code.toUpperCase() },
        });
        await this.audit.log(
          {
            actorId,
            action: 'dishType.created',
            entityType: 'DishType',
            entityId: row.id,
            after: input,
          },
          tx,
        );
        return { id: row.id };
      });
    } catch (error) {
      if (isUniqueViolation(error)) {
        throw conflict('Code already exists', [{ path: 'code', message: 'duplicate' }]);
      }
      throw error;
    }
  }

  /** Name, colour, order, usual bounds (BR-13) and active. Deactivated types leave the pickers. */
  async updateType(actorId: string, factoryId: string, id: string, input: DishTypeUpdate) {
    return this.prisma.$transaction(async (tx) => {
      const current = await tx.dishType.findUnique({ where: { id } });
      if (!current || current.factoryId !== factoryId) throw notFound('Dish type not found');
      const min = input.minPerDay !== undefined ? input.minPerDay : current.minPerDay;
      const max = input.maxPerDay !== undefined ? input.maxPerDay : current.maxPerDay;
      if (min !== null && max !== null && min > max) {
        throw invalid('maxPerDay must be at least minPerDay', [
          { path: 'maxPerDay', message: 'min' },
        ]);
      }
      await tx.dishType.update({ where: { id }, data: input });
      await this.audit.log(
        {
          actorId,
          action: 'dishType.updated',
          entityType: 'DishType',
          entityId: id,
          before: {
            name: current.name,
            color: current.color,
            sortOrder: current.sortOrder,
            minPerDay: current.minPerDay,
            maxPerDay: current.maxPerDay,
            active: current.active,
          },
          after: input,
        },
        tx,
      );
      return { id };
    });
  }

  // --- Libraries --------------------------------------------------------------------------

  async listDishes(
    factoryId: string,
    canteenId: string,
    includeArchived: boolean,
  ): Promise<DishView[]> {
    await this.canteenOf(this.prisma, factoryId, canteenId);
    const rows = await this.prisma.dish.findMany({
      where: { factoryId, canteenId, ...(includeArchived ? {} : { archivedAt: null }) },
      include: { type: true },
      orderBy: [{ type: { sortOrder: 'asc' } }, { name: 'asc' }],
    });
    return rows.map(toDishView);
  }

  async listDesserts(
    factoryId: string,
    canteenId: string,
    includeArchived: boolean,
  ): Promise<DessertView[]> {
    await this.canteenOf(this.prisma, factoryId, canteenId);
    const rows = await this.prisma.dessert.findMany({
      where: { factoryId, canteenId, ...(includeArchived ? {} : { archivedAt: null }) },
      orderBy: { name: 'asc' },
    });
    return rows.map((r) => ({
      id: r.id,
      canteenId: r.canteenId,
      name: r.name,
      archivedAt: r.archivedAt?.toISOString() ?? null,
    }));
  }

  async createDish(actorId: string, factoryId: string, input: DishInput): Promise<DishView> {
    return this.writeOnce('dish', () =>
      this.prisma.$transaction(async (tx) => {
        await this.canteenOf(tx, factoryId, input.canteenId);
        await this.activeType(tx, factoryId, input.typeId);
        await this.assertNoDuplicateDish(tx, input.canteenId, input.typeId, input.name, null);
        const row = await tx.dish.create({
          data: { factoryId, ...input },
          include: { type: true },
        });
        await this.audit.log(
          { actorId, action: 'dish.created', entityType: 'Dish', entityId: row.id, after: input },
          tx,
        );
        return toDishView(row);
      }),
    );
  }

  async updateDish(
    actorId: string,
    factoryId: string,
    id: string,
    input: DishUpdate,
  ): Promise<DishView> {
    return this.writeOnce('dish', () =>
      this.prisma.$transaction(async (tx) => {
        const current = await tx.dish.findUnique({ where: { id } });
        if (!current || current.factoryId !== factoryId) throw notFound('Dish not found');
        if (input.typeId !== current.typeId) await this.activeType(tx, factoryId, input.typeId);
        await this.assertNoDuplicateDish(tx, current.canteenId, input.typeId, input.name, id);
        const row = await tx.dish.update({ where: { id }, data: input, include: { type: true } });
        await this.audit.log(
          {
            actorId,
            action: 'dish.updated',
            entityType: 'Dish',
            entityId: id,
            before: { name: current.name, typeId: current.typeId, sessions: current.sessions },
            after: input,
          },
          tx,
        );
        return toDishView(row);
      }),
    );
  }

  async createDessert(
    actorId: string,
    factoryId: string,
    input: DessertInput,
  ): Promise<{ id: string }> {
    return this.writeOnce('dessert', () =>
      this.prisma.$transaction(async (tx) => {
        await this.canteenOf(tx, factoryId, input.canteenId);
        await this.assertNoDuplicateDessert(tx, input.canteenId, input.name, null);
        const row = await tx.dessert.create({ data: { factoryId, ...input } });
        await this.audit.log(
          {
            actorId,
            action: 'dessert.created',
            entityType: 'Dessert',
            entityId: row.id,
            after: input,
          },
          tx,
        );
        return { id: row.id };
      }),
    );
  }

  async updateDessert(actorId: string, factoryId: string, id: string, input: DessertUpdate) {
    return this.writeOnce('dessert', () =>
      this.prisma.$transaction(async (tx) => {
        const current = await tx.dessert.findUnique({ where: { id } });
        if (!current || current.factoryId !== factoryId) throw notFound('Dessert not found');
        await this.assertNoDuplicateDessert(tx, current.canteenId, input.name, id);
        await tx.dessert.update({ where: { id }, data: input });
        await this.audit.log(
          {
            actorId,
            action: 'dessert.updated',
            entityType: 'Dessert',
            entityId: id,
            before: { name: current.name },
            after: input,
          },
          tx,
        );
        return { id };
      }),
    );
  }

  /**
   * Archive (`archived = true`) or restore. Menus already built keep the entry. Restoring is
   * refused when an active entry with the same name was added meanwhile.
   */
  async setArchived(
    library: Library,
    actorId: string,
    factoryId: string,
    id: string,
    archived: boolean,
  ): Promise<{ id: string; archivedAt: string | null }> {
    return this.writeOnce(library, () =>
      this.prisma.$transaction(async (tx: Tx) => {
        const dish = library === 'dish' ? await tx.dish.findUnique({ where: { id } }) : null;
        const current = library === 'dish' ? dish : await tx.dessert.findUnique({ where: { id } });
        if (!current || current.factoryId !== factoryId) throw notFound();
        if ((current.archivedAt !== null) === archived) {
          return { id, archivedAt: current.archivedAt?.toISOString() ?? null };
        }
        if (!archived) {
          if (dish)
            await this.assertNoDuplicateDish(tx, dish.canteenId, dish.typeId, dish.name, id);
          else await this.assertNoDuplicateDessert(tx, current.canteenId, current.name, id);
        }
        const archivedAt = archived ? this.clock.now() : null;
        if (library === 'dish') await tx.dish.update({ where: { id }, data: { archivedAt } });
        else await tx.dessert.update({ where: { id }, data: { archivedAt } });
        await this.audit.log(
          {
            actorId,
            action: `${library}.${archived ? 'archived' : 'restored'}`,
            entityType: library === 'dish' ? 'Dish' : 'Dessert',
            entityId: id,
          },
          tx,
        );
        return { id, archivedAt: archivedAt?.toISOString() ?? null };
      }),
    );
  }

  // --- Excel import -----------------------------------------------------------------------

  /** An empty template with the dish-type codes on a second sheet. */
  async templateWorkbook(factoryId: string): Promise<Buffer> {
    const types = await this.prisma.dishType.findMany({
      where: { factoryId, active: true },
      orderBy: [{ sortOrder: 'asc' }, { name: 'asc' }],
    });
    const wb = new ExcelJS.Workbook();
    const sheet = wb.addWorksheet('Thư viện món');

    sheet.addRow([...LIBRARY_TEMPLATE_HEADER]);
    sheet.getRow(1).font = { bold: true };
    sheet.columns.forEach((c, i) => (c.width = [30, 24, 48][i]));

    const example = types[0];
    if (example) sheet.addRow([LIBRARY_KIND_LABELS.main, example.code, 'Ví dụ: Cá kho tộ']);
    sheet.addRow([LIBRARY_KIND_LABELS.dessert, '', 'Ví dụ: Chuối']);

    const lists = wb.addWorksheet(DISH_TYPE_SHEET);
    lists.addRow(['Mã loại món', 'Tên']);
    lists.getRow(1).font = { bold: true };
    for (const t of types) lists.addRow([t.code, t.name]);
    addLibraryDropdowns(sheet, types.length);
    return Buffer.from(await wb.xlsx.writeBuffer());
  }

  /**
   * Imports a file into one canteen's library. `apply = false` only validates and previews.
   * One invalid row rejects the file; rows already in the library are skipped, so nothing is
   * ever added twice. Applying writes every new entry and one audit entry in one transaction.
   */
  async importLibrary(
    actorId: string,
    factoryId: string,
    canteenId: string,
    buffer: Buffer,
    apply: boolean,
  ): Promise<LibraryImportView> {
    const raw = await this.parseLibrary(buffer);
    const run = async (tx: Tx): Promise<LibraryImportView> => {
      const canteen = await this.canteenOf(tx, factoryId, canteenId);
      if (apply && !canteen.active) throw invalidState('Canteen is inactive');
      const [types, dishes, desserts] = await Promise.all([
        tx.dishType.findMany({ where: { factoryId, active: true } }),
        tx.dish.findMany({ where: { canteenId }, include: { type: true } }),
        tx.dessert.findMany({ where: { canteenId } }),
      ]);
      const result = validateLibraryRows(raw, {
        typeCodes: new Set(types.map((t) => t.code)),
        existingDishes: new Set(dishes.map((d) => libraryDishKey(d.type.code, d.name))),
        existingDesserts: new Set(desserts.map((d) => libraryNameKey(d.name))),
      });
      if (!result.ok) {
        return {
          status: 'REJECTED',
          rowCount: raw.length,
          errors: result.errors,
          toCreate: [],
          alreadyInLibrary: 0,
        };
      }
      if (apply && result.toCreate.length > 0) {
        const typeId = new Map(types.map((t) => [t.code, t.id]));
        for (const row of result.toCreate) {
          if (row.kind === 'MAIN') {
            await tx.dish.create({
              data: {
                factoryId,
                canteenId,
                name: row.name,
                typeId: typeId.get(row.typeCode!)!,
                sessions: ['LUNCH'],
              },
            });
          } else {
            await tx.dessert.create({ data: { factoryId, canteenId, name: row.name } });
          }
        }
        await this.audit.log(
          {
            actorId,
            action: 'dishLibrary.imported',
            entityType: 'Canteen',
            entityId: canteenId,
            after: {
              created: result.toCreate.map((r) => ({
                kind: r.kind,
                type: r.typeCode,
                name: r.name,
              })),
              skipped: result.alreadyInLibrary.length,
            },
          },
          tx,
        );
      }
      return {
        status: apply ? 'APPLIED' : 'PREVIEW',
        rowCount: raw.length,
        errors: [],
        toCreate: result.toCreate,
        alreadyInLibrary: result.alreadyInLibrary.length,
      };
    };
    if (!apply) return run(this.prisma as unknown as Tx);
    return this.writeOnce('dish', () => this.prisma.$transaction(run, { timeout: 30_000 }));
  }

  private async parseLibrary(buffer: Buffer): Promise<RawLibraryRow[]> {
    const wb = new ExcelJS.Workbook();
    try {
      await wb.xlsx.load(buffer as unknown as ArrayBuffer);
    } catch {
      throw invalid('Not a readable .xlsx file', [{ path: 'file', message: 'FILE_UNREADABLE' }]);
    }
    const sheet = wb.worksheets[0];
    if (!sheet || cellText(sheet.getRow(1), 1) !== LIBRARY_TEMPLATE_HEADER[0]) {
      throw invalid('The file does not use the library template', [
        { path: 'file', message: 'TEMPLATE_MISMATCH' },
      ]);
    }
    const rows: RawLibraryRow[] = [];
    sheet.eachRow({ includeEmpty: false }, (row, rowNumber) => {
      if (rowNumber === 1) return;
      const [kind, typeCode, name] = [1, 2, 3].map((c) => cellText(row, c));
      if (!kind && !typeCode && !name) return;
      rows.push({ rowNumber, kind: kind!, typeCode: typeCode!, name: name! });
    });
    if (rows.length === 0) {
      throw invalid('The file has no rows', [{ path: 'file', message: 'EMPTY_FILE' }]);
    }
    if (rows.length > MAX_IMPORT_ROWS) {
      throw invalid('Too many rows', [{ path: 'file', message: 'TOO_MANY_ROWS' }]);
    }
    return rows;
  }

  // --- Helpers ----------------------------------------------------------------------------

  private async canteenOf(db: Tx | PrismaService, factoryId: string, canteenId: string) {
    const canteen = await db.canteen.findUnique({ where: { id: canteenId } });
    if (!canteen || canteen.factoryId !== factoryId) throw notFound('Canteen not found');
    return canteen;
  }

  private async activeType(tx: Tx, factoryId: string, typeId: string) {
    const type = await tx.dishType.findUnique({ where: { id: typeId } });
    if (!type || type.factoryId !== factoryId || !type.active) {
      throw invalid('Dish type not available', [{ path: 'typeId', message: typeId }]);
    }
  }

  /** Same canteen, same type, same name ignoring case and spacing; archived entries count too. */
  private async assertNoDuplicateDish(
    tx: Tx,
    canteenId: string,
    typeId: string,
    name: string,
    exceptId: string | null,
  ) {
    const key = libraryNameKey(name);
    const rows = await tx.dish.findMany({
      where: { canteenId, typeId, name: { equals: name, mode: 'insensitive' } },
      select: { id: true, name: true, archivedAt: true },
    });
    const same = rows.find((r) => r.id !== exceptId && libraryNameKey(r.name) === key);
    if (same) throw duplicate('dish', same.id, same.archivedAt !== null);
  }

  private async assertNoDuplicateDessert(
    tx: Tx,
    canteenId: string,
    name: string,
    exceptId: string | null,
  ) {
    const key = libraryNameKey(name);
    const rows = await tx.dessert.findMany({
      where: { canteenId, name: { equals: name, mode: 'insensitive' } },
      select: { id: true, name: true, archivedAt: true },
    });
    const same = rows.find((r) => r.id !== exceptId && libraryNameKey(r.name) === key);
    if (same) throw duplicate('dessert', same.id, same.archivedAt !== null);
  }

  /** Two identical requests at once: the partial unique index stops the second one. */
  private async writeOnce<T>(library: Library, write: () => Promise<T>): Promise<T> {
    try {
      return await write();
    } catch (error) {
      if (isUniqueViolation(error)) {
        throw conflict(`This ${library} is already in the library`, [
          { path: 'name', message: 'DUPLICATE' },
        ]);
      }
      throw error;
    }
  }
}

export function toDishView(row: {
  id: string;
  canteenId: string;
  name: string;
  typeId: string;
  type: { id: string; name: string; color: string };
  sessions: DishView['sessions'];
  archivedAt: Date | null;
}): DishView {
  return {
    id: row.id,
    canteenId: row.canteenId,
    name: row.name,
    typeId: row.typeId,
    type: { id: row.type.id, name: row.type.name, color: row.type.color },
    sessions: row.sessions,
    displayName: mealDisplayName(row.type.name, row.name),
    archivedAt: row.archivedAt?.toISOString() ?? null,
  };
}
