import { describe, expect, it } from 'vitest';
import { lastAcceptedMinute } from './deadlines';
import { toVnDateTime } from './time';

describe('BR-03: how a closeAt is shown', () => {
  it('BR-03: the UI shows 23:59 Thursday for a closeAt of 00:00 Friday', () => {
    // closeAt of week 2026-10-26 is 00:00 Friday 2026-10-23 Vietnam time (the API computes it).
    const shown = toVnDateTime(lastAcceptedMinute(new Date('2026-10-22T17:00:00.000Z')));
    expect([shown.date, shown.isoWeekday, shown.hour, shown.minute]).toEqual([
      '2026-10-22',
      4,
      23,
      59,
    ]);
  });
});
