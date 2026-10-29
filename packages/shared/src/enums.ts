/** Roles stack on one employee account (plan section 3). EMPLOYEE is implicit for every active employee. */
export const ROLES = ['EMPLOYEE', 'DEPT_ADMIN', 'HC_ADMIN', 'SYS_ADMIN'] as const;
export type Role = (typeof ROLES)[number];

/** In-app notices (BR-14). */
export const NOTIFICATION_TYPES = ['MENU_DISH_REPLACED', 'MENU_DISH_REMOVED'] as const;
export type NotificationType = (typeof NOTIFICATION_TYPES)[number];

/** Machine-readable error codes the API returns; the web maps them to Vietnamese copy. */
export const API_ERROR_CODES = [
  'UNAUTHENTICATED',
  'FORBIDDEN',
  'NOT_FOUND',
  'VALIDATION_FAILED',
  'INVALID_CREDENTIALS',
  'PIN_LOCKED',
  'PIN_CHANGE_REQUIRED',
  'PIN_UNCHANGED',
  'SSO_NOT_LINKED',
  'SSO_FAILED',
  /** A unique code or name is already taken. */
  'CONFLICT',
  /** Master data still mapped somewhere cannot be deactivated. */
  'IN_USE',
  /** The resource is in a state that does not allow this action (e.g. already published). */
  'INVALID_STATE',
  /** BR-24: the day has locked (day.lockTime) for this kind of change. */
  'DAY_LOCKED',
  /** A serving day of the menu lacks a main dish or a dessert (publishing rule, 2026-10-23). */
  'MENU_INCOMPLETE',
  /** A DMP call failed; nothing was written. */
  'DMP_UNAVAILABLE',
  'INTERNAL',
] as const;
export type ApiErrorCode = (typeof API_ERROR_CODES)[number];
