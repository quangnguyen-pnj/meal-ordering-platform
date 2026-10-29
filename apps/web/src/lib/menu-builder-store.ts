import { menuWeekGaps, type MenuDayGap } from '@meal/shared';
import type { MenuWeekView } from '@/lib/api-types';
import { createStore } from 'zustand/vanilla';

export interface DraftItem {
  /** Existing menu item; absent for a dish added in this session. Keeping it marks a BR-14 replace. */
  id?: string;
  dishId: string;
}

export interface DraftDay {
  date: string;
  isServing: boolean;
  items: DraftItem[];
  dessertIds: string[];
}

export interface MenuBuilderState {
  days: DraftDay[];
  dirty: boolean;
  load: (view: MenuWeekView) => void;
  addDish: (date: string, dishId: string) => void;
  removeItem: (date: string, index: number) => void;
  replaceItem: (date: string, index: number, dishId: string) => void;
  moveItem: (date: string, index: number, delta: -1 | 1) => void;
  setServing: (date: string, isServing: boolean) => void;
  addDessert: (date: string, dessertId: string) => void;
  removeDessert: (date: string, dessertId: string) => void;
}

/**
 * The menu builder's working copy of one week (plan 6.1: Zustand only for the menu builder).
 * Rules enforced while editing: the same dish appears once per day (BR-11) and a non-serving day
 * holds no dishes (BR-08). The API checks the same again.
 */
export function createMenuBuilderStore() {
  return createStore<MenuBuilderState>()((set) => {
    const updateDay = (date: string, fn: (d: DraftDay) => DraftDay) =>
      set((s) => ({ dirty: true, days: s.days.map((d) => (d.date === date ? fn(d) : d)) }));

    return {
      days: [],
      dirty: false,
      load: (view) =>
        set({
          dirty: false,
          days: view.days.map((d) => ({
            date: d.date,
            isServing: d.isServing,
            items: d.items.map((i) => ({ id: i.id, dishId: i.dishId })),
            dessertIds: d.desserts.map((x) => x.id),
          })),
        }),
      addDish: (date, dishId) =>
        updateDay(date, (d) =>
          !d.isServing || d.items.some((i) => i.dishId === dishId)
            ? d
            : { ...d, items: [...d.items, { dishId }] },
        ),
      removeItem: (date, index) =>
        updateDay(date, (d) => ({ ...d, items: d.items.filter((_, i) => i !== index) })),
      replaceItem: (date, index, dishId) =>
        updateDay(date, (d) =>
          d.items.some((i, j) => j !== index && i.dishId === dishId)
            ? d
            : { ...d, items: d.items.map((i, j) => (j === index ? { ...i, dishId } : i)) },
        ),
      moveItem: (date, index, delta) =>
        updateDay(date, (d) => {
          const target = index + delta;
          if (target < 0 || target >= d.items.length) return d;
          const items = [...d.items];
          [items[index], items[target]] = [items[target]!, items[index]!];
          return { ...d, items };
        }),
      setServing: (date, isServing) =>
        updateDay(date, (d) =>
          isServing ? { ...d, isServing } : { ...d, isServing, items: [], dessertIds: [] },
        ),
      addDessert: (date, dessertId) =>
        updateDay(date, (d) =>
          !d.isServing || d.dessertIds.includes(dessertId)
            ? d
            : { ...d, dessertIds: [...d.dessertIds, dessertId] },
        ),
      removeDessert: (date, dessertId) =>
        updateDay(date, (d) => ({ ...d, dessertIds: d.dessertIds.filter((x) => x !== dessertId) })),
    };
  });
}

export type MenuBuilderStore = ReturnType<typeof createMenuBuilderStore>;

/**
 * The serving days that keep the draft from being published: each needs at least one main dish
 * and one dessert (product owner, 2026-10-23). The API checks the same again.
 */
export function draftGaps(days: readonly DraftDay[]): Array<{ date: string; gaps: MenuDayGap[] }> {
  return menuWeekGaps(
    days.map((d) => ({
      date: d.date,
      isServing: d.isServing,
      mainCount: d.items.length,
      dessertCount: d.dessertIds.length,
    })),
  );
}
