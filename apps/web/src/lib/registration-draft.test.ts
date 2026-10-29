import type { MyDayView } from '@/lib/api-types';
import { describe, expect, it } from 'vitest';
import {
  changedDates,
  draftFromDays,
  forecastGrid,
  saveBody,
  setQuantity,
} from './registration-draft';

const day = (
  date: string,
  items: Array<[string, 'CHAY' | 'CHAO' | 'MAN', number]>,
  window: MyDayView['window'] = 'OPEN',
): MyDayView => ({
  date,
  window,
  lockAt: '',
  items: items.map(([menuItemId, type, quantity]) => ({
    menuItemId,
    type: { id: type, name: type, color: '#000000' },
    quantity,
    forecast: 0,
    displayName: menuItemId,
    replacedAfterClose: null,
  })),
  needsReselect: [],
  desserts: [],
  actualTotal: 0,
  forecastTotal: null,
  changed: false,
  extra: false,
});

const week = [
  day('2026-11-02', [
    ['mon-chay', 'CHAY', 0],
    ['mon-ca', 'MAN', 1],
  ]),
  day('2026-11-03', [
    ['tue-chao', 'CHAO', 0],
    ['tue-ga', 'MAN', 0],
  ]),
  day('2026-11-04', [['wed-thit', 'MAN', 2]], 'LOCKED'),
];

describe('week registration draft', () => {
  it('saves only the editable days that changed, with positive lines only', () => {
    let draft = draftFromDays(week);
    expect(changedDates(week, draft)).toEqual([]);
    draft = setQuantity(draft, '2026-11-03', 'tue-ga', 3);
    draft = setQuantity(draft, '2026-11-02', 'mon-ca', 0);
    // A locked day is never sent (BR-24), whatever the draft says.
    draft = setQuantity(draft, '2026-11-04', 'wed-thit', 5);
    expect(saveBody('2026-11-02', week, draft)).toEqual({
      weekStart: '2026-11-02',
      days: [
        { date: '2026-11-02', lines: [] },
        { date: '2026-11-03', lines: [{ menuItemId: 'tue-ga', quantity: 3 }] },
      ],
    });
  });

  it('BR-20: quantities never go below zero and stay integers', () => {
    const draft = setQuantity(draftFromDays(week), '2026-11-02', 'mon-ca', -2);
    expect(draft['2026-11-02']!['mon-ca']).toBe(0);
    expect(setQuantity(draft, '2026-11-02', 'mon-ca', 2.7)['2026-11-02']!['mon-ca']).toBe(2);
  });
});

describe('forecast grid', () => {
  const withForecast = (
    d: MyDayView,
    forecast: Record<string, number>,
    forecastTotal: number,
  ): MyDayView => ({
    ...d,
    forecastTotal,
    items: d.items.map((i) => ({ ...i, forecast: forecast[i.menuItemId] ?? 0 })),
  });

  it('BR-07: lists only dishes with a forecast, one row per dish across the week', () => {
    const days = [
      withForecast(
        day('2026-11-02', [
          ['ga', 'MAN', 2],
          ['chay', 'CHAY', 0],
        ]),
        { ga: 1 },
        1,
      ),
      withForecast(day('2026-11-03', [['ga', 'MAN', 0]]), { ga: 2 }, 2),
      withForecast(day('2026-11-04', [['ca', 'MAN', 0]]), {}, 0),
    ];
    const grid = forecastGrid(days);
    expect(grid.rows).toEqual([
      expect.objectContaining({
        displayName: 'ga',
        byDate: { '2026-11-02': 1, '2026-11-03': 2 },
        total: 3,
      }),
    ]);
    expect(grid.dayTotals).toEqual({ '2026-11-02': 1, '2026-11-03': 2, '2026-11-04': 0 });
    expect(grid.total).toBe(3);
    expect(grid.other).toEqual({});
  });

  it('BR-14: forecast on a dish replaced after the snapshot still counts in the day total', () => {
    const days = [withForecast(day('2026-11-02', [['moi', 'MAN', 1]]), {}, 1)];
    const grid = forecastGrid(days);
    expect(grid.rows).toEqual([]);
    expect(grid.other).toEqual({ '2026-11-02': 1 });
    expect(grid.total).toBe(1);
  });
});
