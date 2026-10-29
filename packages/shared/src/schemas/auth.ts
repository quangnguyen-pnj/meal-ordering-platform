import { z } from 'zod';
import { API_ERROR_CODES, ROLES } from '../enums';

export const PIN_LENGTH = 6;

export const PinSchema = z.string().regex(/^\d{6}$/, 'PIN must be exactly 6 digits');

export const EmployeeCodeSchema = z.string().trim().min(1).max(32);

export const PinLoginSchema = z.object({
  employeeCode: EmployeeCodeSchema,
  pin: PinSchema,
});
export type PinLoginInput = z.infer<typeof PinLoginSchema>;

export const ChangePinSchema = z
  .object({ currentPin: PinSchema, newPin: PinSchema })
  .refine((v) => v.currentPin !== v.newPin, {
    path: ['newPin'],
    message: 'New PIN must differ from the current PIN',
  });
export type ChangePinInput = z.infer<typeof ChangePinSchema>;

export const SessionUserSchema = z.object({
  id: z.string(),
  employeeCode: z.string(),
  name: z.string(),
  email: z.string().nullable(),
  roles: z.array(z.enum(ROLES)),
  factoryId: z.string(),
  mealUnitId: z.string().nullable(),
  /** Org nodes a DEPT_ADMIN manages (including their children). */
  deptAdminOrgUnitIds: z.array(z.string()),
  pinMustChange: z.boolean(),
});
export type SessionUser = z.infer<typeof SessionUserSchema>;

export const ApiErrorSchema = z.object({
  statusCode: z.number().int(),
  code: z.enum(API_ERROR_CODES),
  message: z.string(),
  details: z.unknown().optional(),
  /** ISO instant until which the account is locked (PIN_LOCKED only). */
  lockedUntil: z.string().optional(),
});
export type ApiErrorBody = z.infer<typeof ApiErrorSchema>;
