import { describe, expect, it } from 'vitest';
import { ChangePinSchema, PinLoginSchema } from '../schemas/auth';
import { SETTING_KEYS, defaultSettingValue, isSettingKey, parseSettingValue } from '../settings';

describe('auth schemas', () => {
  it('accepts exactly six digits', () => {
    expect(PinLoginSchema.safeParse({ employeeCode: 'A1', pin: '123456' }).success).toBe(true);
    expect(PinLoginSchema.safeParse({ employeeCode: 'A1', pin: '12345' }).success).toBe(false);
    expect(PinLoginSchema.safeParse({ employeeCode: 'A1', pin: '12a456' }).success).toBe(false);
  });

  it('requires the new PIN to differ from the current one', () => {
    expect(ChangePinSchema.safeParse({ currentPin: '111111', newPin: '111111' }).success).toBe(
      false,
    );
    expect(ChangePinSchema.safeParse({ currentPin: '111111', newPin: '222222' }).success).toBe(
      true,
    );
  });
});

describe('settings (plan 6.4)', () => {
  it('every default validates against its own schema', () => {
    for (const key of SETTING_KEYS) {
      expect(() => parseSettingValue(key, defaultSettingValue(key)), key).not.toThrow();
    }
  });

  it('BR-03: registration.close defaults to Thursday 23:59', () => {
    expect(defaultSettingValue('registration.close')).toEqual({ weekday: 4, time: '23:59' });
  });

  it('holds the 17 documented keys', () => {
    expect(SETTING_KEYS).toHaveLength(17);
    expect(isSettingKey('pin.maxAttempts')).toBe(true);
    expect(isSettingKey('nope')).toBe(false);
  });

  it('rejects malformed values', () => {
    expect(() => parseSettingValue('day.lockTime', '8:00')).toThrow();
    expect(() => parseSettingValue('delivery.tolerancePct', 150)).toThrow();
  });
});
