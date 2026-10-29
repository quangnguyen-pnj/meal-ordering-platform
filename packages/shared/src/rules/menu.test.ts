import { describe, expect, it } from 'vitest';
import { checkMenuMix, menuDayGaps, menuWeekGaps, type MenuMixRule } from './menu';

describe('BR-13: usual menu mix', () => {
  const rules: MenuMixRule[] = [
    { typeId: 'chay', typeName: 'Chay', minPerDay: 1, maxPerDay: 1 },
    { typeId: 'chao', typeName: 'Cháo', minPerDay: 1, maxPerDay: 1 },
    { typeId: 'man', typeName: 'Mặn', minPerDay: 2, maxPerDay: 3 },
    { typeId: 'clean', typeName: 'Eat clean', minPerDay: null, maxPerDay: null },
  ];

  it('BR-13: 1 Chay + 1 Cháo + 2 or 3 Mặn is the usual mix', () => {
    expect(checkMenuMix(['chay', 'chao', 'man', 'man'], rules).usual).toBe(true);
    expect(checkMenuMix(['man', 'chay', 'man', 'chao', 'man'], rules).usual).toBe(true);
  });

  it('BR-13: a different mix reports what is off', () => {
    const check = checkMenuMix(['man', 'man', 'man', 'man', 'chay'], rules);
    expect(check.usual).toBe(false);
    expect(check.issues).toEqual([
      { typeId: 'chao', typeName: 'Cháo', actual: 0, min: 1, max: 1 },
      { typeId: 'man', typeName: 'Mặn', actual: 4, min: 2, max: 3 },
    ]);
  });

  it('BR-13: a type without bounds is never an issue, and an empty day is unusual', () => {
    expect(checkMenuMix(['chay', 'chao', 'man', 'man', 'clean', 'clean'], rules).usual).toBe(true);
    expect(checkMenuMix([], rules).issues).toHaveLength(3);
  });

  it('BR-13: only one bound can be set', () => {
    const atMostOne = [{ typeId: 'clean', typeName: 'Eat clean', minPerDay: null, maxPerDay: 1 }];
    expect(checkMenuMix([], atMostOne).usual).toBe(true);
    expect(checkMenuMix(['clean', 'clean'], atMostOne).issues[0]).toMatchObject({ actual: 2 });
  });
});

describe('publishing needs a main dish and a dessert on every serving day', () => {
  it('a serving day lacking either is reported; a day off never is', () => {
    expect(menuDayGaps({ isServing: true, mainCount: 2, dessertCount: 1 })).toEqual([]);
    expect(menuDayGaps({ isServing: true, mainCount: 0, dessertCount: 0 })).toEqual([
      'MAIN',
      'DESSERT',
    ]);
    expect(menuDayGaps({ isServing: true, mainCount: 3, dessertCount: 0 })).toEqual(['DESSERT']);
    expect(menuDayGaps({ isServing: false, mainCount: 0, dessertCount: 0 })).toEqual([]);
  });

  it('lists only the days that block publishing', () => {
    expect(
      menuWeekGaps([
        { date: '2026-11-02', isServing: true, mainCount: 4, dessertCount: 1 },
        { date: '2026-11-03', isServing: true, mainCount: 0, dessertCount: 1 },
        { date: '2026-11-07', isServing: false, mainCount: 0, dessertCount: 0 },
      ]),
    ).toEqual([{ date: '2026-11-03', gaps: ['MAIN'] }]);
  });
});
