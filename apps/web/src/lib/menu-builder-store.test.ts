import type { MenuWeekView } from '@/lib/api-types';
import { describe, expect, it } from 'vitest';
import { createMenuBuilderStore, draftGaps } from './menu-builder-store';

const MAN = { id: 'dt_MAN', name: 'Mặn', color: '#b91c1c' };
const view = {
  id: 'w1',
  canteenId: 'c1',
  canteenName: 'Nhà ăn',
  weekStart: '2027-03-01',
  status: 'PUBLISHED',
  publishedAt: null,
  closeAt: '2027-02-25T17:00:00.000Z',
  closeAtIsExpected: false,
  latePublish: false,
  lockedDates: [],
  days: [
    {
      date: '2027-03-01',
      isServing: true,
      items: [
        { id: 'i1', dishId: 'ca', dishName: 'Cá', type: MAN, displayName: '[Mặn] Cá' },
        { id: 'i2', dishId: 'thit', dishName: 'Thịt', type: MAN, displayName: '[Mặn] Thịt' },
      ],
      desserts: [{ id: 'chuoi', name: 'Chuối' }],
      mix: null,
    },
    { date: '2027-03-06', isServing: false, items: [], desserts: [], mix: null },
  ],
} satisfies MenuWeekView;

describe('menu builder store', () => {
  it('loads a week without marking it dirty', () => {
    const store = createMenuBuilderStore();
    store.getState().load(view);
    expect(store.getState().dirty).toBe(false);
    expect(store.getState().days[0]!.items).toEqual([
      { id: 'i1', dishId: 'ca' },
      { id: 'i2', dishId: 'thit' },
    ]);
  });

  it('BR-11: adds a dragged dish once per day, never twice', () => {
    const store = createMenuBuilderStore();
    store.getState().load(view);
    store.getState().addDish('2027-03-01', 'chay');
    store.getState().addDish('2027-03-01', 'chay');
    store.getState().addDish('2027-03-01', 'ca');
    expect(store.getState().days[0]!.items.map((i) => i.dishId)).toEqual(['ca', 'thit', 'chay']);
    expect(store.getState().dirty).toBe(true);
  });

  it('BR-14: replacing keeps the item id so the API moves its registrations', () => {
    const store = createMenuBuilderStore();
    store.getState().load(view);
    store.getState().replaceItem('2027-03-01', 0, 'suon');
    expect(store.getState().days[0]!.items[0]).toEqual({ id: 'i1', dishId: 'suon' });
    // A replacement may not duplicate another dish of the day.
    store.getState().replaceItem('2027-03-01', 0, 'thit');
    expect(store.getState().days[0]!.items[0]).toEqual({ id: 'i1', dishId: 'suon' });
  });

  it('BR-08: dishes cannot go on a non-serving day; turning a day off clears it', () => {
    const store = createMenuBuilderStore();
    store.getState().load(view);
    store.getState().addDish('2027-03-06', 'ca');
    expect(store.getState().days[1]!.items).toEqual([]);
    store.getState().setServing('2027-03-06', true);
    store.getState().addDish('2027-03-06', 'ca');
    store.getState().addDessert('2027-03-06', 'chuoi');
    store.getState().setServing('2027-03-06', false);
    expect(store.getState().days[1]).toEqual({
      date: '2027-03-06',
      isServing: false,
      items: [],
      dessertIds: [],
    });
  });

  it('reorders and removes items and desserts', () => {
    const store = createMenuBuilderStore();
    store.getState().load(view);
    store.getState().moveItem('2027-03-01', 1, -1);
    store.getState().moveItem('2027-03-01', 0, -1); // already first: no change
    expect(store.getState().days[0]!.items.map((i) => i.dishId)).toEqual(['thit', 'ca']);
    store.getState().removeItem('2027-03-01', 0);
    store.getState().removeDessert('2027-03-01', 'chuoi');
    expect(store.getState().days[0]).toMatchObject({ items: [{ id: 'i1' }], dessertIds: [] });
  });

  it('publishing waits for a main dish and a dessert on every serving day', () => {
    const store = createMenuBuilderStore();
    store.getState().load(view);
    expect(draftGaps(store.getState().days)).toEqual([]);
    store.getState().removeDessert('2027-03-01', 'chuoi');
    store.getState().setServing('2027-03-06', true);
    expect(draftGaps(store.getState().days)).toEqual([
      { date: '2027-03-01', gaps: ['DESSERT'] },
      { date: '2027-03-06', gaps: ['MAIN', 'DESSERT'] },
    ]);
  });
});
