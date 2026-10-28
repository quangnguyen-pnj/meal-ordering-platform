import { ForbiddenException } from '@nestjs/common';
import { defaultSettingValue, isRegistrationOpen, regularCloseAt } from '@meal/shared';
import { loadEnv } from '../config/env';
import { ClockService } from './clock.service';

const baseEnv = {
  DATABASE_URL: 'postgresql://x:x@localhost:5432/x',
  SESSION_SECRET: 'test-secret-test-secret-test-secret-1234',
};
const clockWith = (extra: Record<string, string>) =>
  new ClockService(loadEnv({ ...baseEnv, ...extra }));

describe('ClockService', () => {
  it('returns real time when fake time is not set', () => {
    const clock = clockWith({ NODE_ENV: 'test', CLOCK_FAKE_ENABLED: 'true' });
    const before = Date.now();
    const now = clock.now().getTime();
    expect(now).toBeGreaterThanOrEqual(before);
    expect(clock.isFake()).toBe(false);
  });

  it('freezes at the fake instant until advanced or cleared', () => {
    const clock = clockWith({ NODE_ENV: 'test', CLOCK_FAKE_ENABLED: 'true' });
    clock.setFakeTime(new Date('2026-10-22T16:59:30.000Z'));
    expect(clock.now().toISOString()).toBe('2026-10-22T16:59:30.000Z');
    clock.advanceFakeTime(30_000);
    expect(clock.now().toISOString()).toBe('2026-10-22T17:00:00.000Z');
    clock.clearFakeTime();
    expect(clock.isFake()).toBe(false);
  });

  it('BR-03: fake time at Thursday 23:59:30 and at Friday 00:00 gives different registration results', () => {
    const clock = clockWith({ NODE_ENV: 'test', CLOCK_FAKE_ENABLED: 'true' });
    const closeAt = regularCloseAt('2026-10-26', defaultSettingValue('registration.close'));

    clock.setFakeTime(new Date('2026-10-22T23:59:30+07:00'));
    const thursdayNight = isRegistrationOpen(clock.now(), closeAt);

    clock.setFakeTime(new Date('2026-10-23T00:00:00+07:00'));
    const fridayMidnight = isRegistrationOpen(clock.now(), closeAt);

    expect(thursdayNight).toBe(true);
    expect(fridayMidnight).toBe(false);
  });

  it('refuses fake time unless CLOCK_FAKE_ENABLED is set', () => {
    const clock = clockWith({ NODE_ENV: 'development' });
    expect(clock.fakeTimeAllowed).toBe(false);
    expect(() => clock.setFakeTime(new Date())).toThrow(ForbiddenException);
  });

  it('is rejected outright in production, even if someone sets CLOCK_FAKE_ENABLED', () => {
    expect(() =>
      loadEnv({
        ...baseEnv,
        NODE_ENV: 'production',
        CLOCK_FAKE_ENABLED: 'true',
        ENTRA_MODE: 'oidc',
        ENTRA_TENANT_ID: 't',
        ENTRA_CLIENT_ID: 'c',
        ENTRA_CLIENT_SECRET: 's',
        ENTRA_REDIRECT_URI: 'https://x/cb',
      }),
    ).toThrow(/Fake time is not allowed in production/);
  });
});
