/**
 * The API's request and response shapes, generated from the Java API's OpenAPI description (J3):
 * apps/api/openapi.json → ./openapi.d.ts. After an API change run `pnpm --filter @meal/api openapi`,
 * then `pnpm --filter @meal/web api:types`. Names here follow the screens; the generated names
 * follow the Java records.
 */
import type { API_ERROR_CODES } from '@meal/shared';
import type { components } from './openapi';

type Schemas = components['schemas'];

export type SessionUser = Schemas['SessionUser'];
export type ResetPinResult = Schemas['ResetPinResult'];
export type BannerView = Schemas['BannerView'];
export type SettingView = Schemas['SettingView'];

export type MasterItemView = Schemas['MasterItemView'];
export type DishTypeLabel = Schemas['DishTypeLabel'];
export type DishTypeView = Schemas['DishTypeView'];
export type DishView = Schemas['DishView'];
export type DessertView = Schemas['DessertView'];
export type LibraryImportView = Schemas['LibraryImportView'];
export type LibraryRowErrorCode = Schemas['LibraryImportError']['code'];

export type OrgUnitView = Schemas['OrgUnitView'];
export type OrgFilterOption = Schemas['FilterOption'];
export type EmployeeMappingView = Schemas['EmployeeMappingView'];
export type MappingImportView = Schemas['MappingImportView'];
export type MappingRowErrorCode = Schemas['MappingImportError']['code'];
export type MappingRowField = Schemas['MappingImportError']['field'];

export type MenuWeekView = Schemas['MenuWeekView'];
export type MenuSaveResult = Schemas['MenuSaveResult'];
export type MyMenuWeekView = Schemas['MyMenuWeekView'];

export type SelectionLine = Schemas['LineBody'];
export type MyWeekSave = Schemas['WeekSaveBody'];
export type MyDayView = Schemas['MyDayView'];
export type MyRegistrationWeekView = Schemas['MyWeekView'];
export type MyDayRegistrationView = Schemas['MyDayRegistrationView'];
/** What a save on /week or /today answers: the new view and the days that changed. */
export type SaveRegistrationResult<T> = Omit<Schemas['SaveResultMyWeekView'], 'view'> & { view: T };
export type HistoryView = Schemas['HistoryView'];
export type TeamDayView = Schemas['TeamDayView'];
export type RegistrationUnitOption = Schemas['UnitOption'];
export type RegistrationProgressView = Schemas['ProgressView'];
export type NotRegisteredView = Schemas['NotRegisteredView'];
export type RegistrationGridView = Schemas['GridView'];
export type GridSaveResult = Schemas['GridSaveResult'];
export type RegistrationImportView = Schemas['ImportView'];
export type RegistrationImportErrorCode = Schemas['RegistrationImportError']['code'];

/**
 * The error codes the web has copy for (packages/shared, used to parse error bodies at run time)
 * must be exactly the ones the API sends: this fails to compile when they drift apart.
 */
type Same<A, B> = [A] extends [B] ? ([B] extends [A] ? true : false) : false;
export const errorCodesMatch: Same<
  (typeof API_ERROR_CODES)[number],
  Schemas['ApiErrorBody']['code']
> = true;
