import { z } from 'zod';

/** HH:mm, 24-hour, Asia/Ho_Chi_Minh wall-clock time. */
export const TimeOfDaySchema = z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/, 'Expected HH:mm');

/** ISO weekday: 1 = Monday ... 7 = Sunday. */
export const IsoWeekdaySchema = z.number().int().min(1).max(7);

export const WeekdayTimeSchema = z.object({ weekday: IsoWeekdaySchema, time: TimeOfDaySchema });
export type WeekdayTime = z.infer<typeof WeekdayTimeSchema>;

const Pct = z.number().min(0).max(100);
const PositiveInt = z.number().int().positive();

/**
 * Per-factory settings and their defaults (plan section 6.4). Every deadline is read from here
 * and never hardcoded (plan 7.1 rule 3).
 *
 * `registration.close` stores the LAST accepted minute (Thursday 23:59). The rule functions turn
 * it into the exclusive closeAt instant (Friday 00:00), per BR-03.
 */
export const SETTING_DEFINITIONS = {
  'registration.close': { schema: WeekdayTimeSchema, default: { weekday: 4, time: '23:59' } }, // BR-03
  'registration.latePublishHours': { schema: PositiveInt, default: 24 }, // BR-04
  'menu.publishWarnAt': { schema: WeekdayTimeSchema, default: { weekday: 1, time: '12:00' } }, // BR-06
  servingDays: { schema: z.array(IsoWeekdaySchema).min(1), default: [1, 2, 3, 4, 5] }, // BR-08
  'day.lockTime': { schema: TimeOfDaySchema, default: '08:00' }, // BR-23, BR-24
  'attendance.pollStart': { schema: TimeOfDaySchema, default: '07:00' }, // BR-30
  'attendance.pollIntervalMin': { schema: PositiveInt, default: 5 }, // BR-30
  'attendance.cutoff': { schema: TimeOfDaySchema, default: '08:10' }, // BR-30, BR-31
  'attendance.safetyMinPresentPct': { schema: Pct, default: 50 }, // BR-34
  'reconciliation.dishDeviationPct': { schema: Pct, default: 10 }, // BR-42
  'close.time': { schema: TimeOfDaySchema, default: '08:30' }, // BR-43
  'close.reminderAt': { schema: TimeOfDaySchema, default: '08:45' }, // BR-43
  'close.adjustmentDeadline': { schema: TimeOfDaySchema, default: '10:30' }, // BR-46
  'close.autoFinalizeAt': { schema: TimeOfDaySchema, default: '12:00' }, // BR-43, auto-finalization
  'delivery.tolerancePct': { schema: Pct, default: 5 }, // BR-50
  'delivery.confirmDeadline': { schema: TimeOfDaySchema, default: '15:00' }, // BR-51
  'pin.maxAttempts': { schema: PositiveInt, default: 5 }, // PIN sign-in
  'pin.lockMinutes': { schema: PositiveInt, default: 15 }, // PIN sign-in
} as const;

export type SettingKey = keyof typeof SETTING_DEFINITIONS;
export type SettingValue<K extends SettingKey> = z.infer<(typeof SETTING_DEFINITIONS)[K]['schema']>;

export const SETTING_KEYS = Object.keys(SETTING_DEFINITIONS) as SettingKey[];

export function isSettingKey(key: string): key is SettingKey {
  return Object.prototype.hasOwnProperty.call(SETTING_DEFINITIONS, key);
}

/** Validates a raw (JSON) value for a key; throws a ZodError when it is malformed. */
export function parseSettingValue<K extends SettingKey>(key: K, raw: unknown): SettingValue<K> {
  return SETTING_DEFINITIONS[key].schema.parse(raw) as SettingValue<K>;
}

export function defaultSettingValue<K extends SettingKey>(key: K): SettingValue<K> {
  return SETTING_DEFINITIONS[key].default as SettingValue<K>;
}
