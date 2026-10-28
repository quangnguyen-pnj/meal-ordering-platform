import { ForbiddenException, Inject, Injectable } from '@nestjs/common';
import { ENV, type AppEnv } from '../config/env';

/**
 * The only place in the api that reads the wall clock (plan 7.1 rule 3). Business code calls
 * `now()` and passes the result into pure rule functions.
 *
 * Fake-time mode freezes `now()` at a chosen instant so tests and demos can walk through
 * deadlines (e.g. Thursday 23:59:30 vs Friday 00:00). It is enabled with CLOCK_FAKE_ENABLED and
 * is refused when NODE_ENV=production, both here and at env validation.
 */
@Injectable()
export class ClockService {
  private fakeNow: Date | null = null;

  constructor(@Inject(ENV) private readonly env: AppEnv) {}

  get fakeTimeAllowed(): boolean {
    return this.env.NODE_ENV !== 'production' && this.env.CLOCK_FAKE_ENABLED;
  }

  /** Current instant: the frozen fake time when set, otherwise the real time. */
  now(): Date {
    return this.fakeNow ? new Date(this.fakeNow.getTime()) : new Date();
  }

  isFake(): boolean {
    return this.fakeNow !== null;
  }

  setFakeTime(at: Date): void {
    this.assertAllowed();
    if (Number.isNaN(at.getTime())) throw new RangeError('Invalid fake time');
    this.fakeNow = new Date(at.getTime());
  }

  advanceFakeTime(ms: number): void {
    this.assertAllowed();
    if (!this.fakeNow) throw new Error('Fake time is not set');
    this.fakeNow = new Date(this.fakeNow.getTime() + ms);
  }

  clearFakeTime(): void {
    this.fakeNow = null;
  }

  private assertAllowed(): void {
    if (!this.fakeTimeAllowed) {
      throw new ForbiddenException('Fake time is disabled in this environment');
    }
  }
}
