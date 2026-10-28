package vn.pnj.meal.api.common;

/**
 * Machine-readable error codes; the web maps each one to Vietnamese copy. Must stay equal to {@code
 * API_ERROR_CODES} in {@code packages/shared/src/enums.ts} until the web reads them from OpenAPI.
 */
public enum ApiErrorCode {
  UNAUTHENTICATED,
  FORBIDDEN,
  NOT_FOUND,
  VALIDATION_FAILED,
  INVALID_CREDENTIALS,
  PIN_LOCKED,
  PIN_CHANGE_REQUIRED,
  PIN_UNCHANGED,
  SSO_NOT_LINKED,
  SSO_FAILED,
  /** A unique code or name is already taken. */
  CONFLICT,
  /** Master data still mapped somewhere cannot be deactivated. */
  IN_USE,
  /** The resource is in a state that does not allow this action (e.g. already published). */
  INVALID_STATE,
  /** BR-24: the day has locked (day.lockTime) for this kind of change. */
  DAY_LOCKED,
  /** A serving day of the menu lacks a main dish or a dessert (publishing rule, 2026-10-23). */
  MENU_INCOMPLETE,
  /** A DMP call failed; nothing was written. */
  DMP_UNAVAILABLE,
  INTERNAL
}
