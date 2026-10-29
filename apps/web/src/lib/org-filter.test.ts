import type { OrgFilterOption } from '@/lib/api-types';
import { describe, expect, it } from 'vitest';
import { orgFilterChange, orgFilterLevels } from './org-filter';

const node = (id: string, parentId: string | null, level: number, levelName: string) =>
  ({ id, name: id, parentId, level, levelName }) satisfies OrgFilterOption;
const tree = [
  node('pnjp', null, 1, 'Công ty'),
  node('ksx', 'pnjp', 2, 'Khối'),
  node('kvp', 'pnjp', 2, 'Khối'),
  node('xct', 'ksx', 3, 'Phòng/Xưởng'),
  node('ttcn', 'ksx', 3, 'Trung tâm'),
  node('xkh', 'ksx', 3, 'Phòng/Xưởng'),
  node('tonhan', 'xct', 4, 'Bộ phận'),
  node('nhom', 'tonhan', 5, 'Tổ'),
];

describe('Khối / Trung tâm / Phòng ban filter', () => {
  it('starts below the company with the Khối select', () => {
    const levels = orgFilterLevels(tree, null);
    expect(levels).toHaveLength(1);
    expect(levels[0]).toMatchObject({ label: 'Khối', value: '' });
    expect(levels[0]!.options.map((o) => o.id)).toEqual(['ksx', 'kvp']);
  });

  it('opens one select per chosen level, labelled with the usual level name', () => {
    const levels = orgFilterLevels(tree, 'tonhan');
    expect(levels.map((l) => [l.label, l.value])).toEqual([
      ['Khối', 'ksx'],
      ['Phòng/Xưởng', 'xct'],
      ['Bộ phận', 'tonhan'],
    ]);
  });

  it('clearing a select keeps the parent choice', () => {
    const levels = orgFilterLevels(tree, 'xct');
    expect(orgFilterChange(levels, 1, '')).toBe('ksx');
    expect(orgFilterChange(levels, 0, '')).toBe('');
    expect(orgFilterChange(levels, 1, 'ttcn')).toBe('ttcn');
  });

  it('a dept admin sees their own subtree at the top', () => {
    const scoped = tree.filter((n) => ['xct', 'tonhan', 'nhom'].includes(n.id));
    const levels = orgFilterLevels(scoped, null);
    expect(levels[0]!.options.map((o) => o.id)).toEqual(['tonhan']);
    expect(levels[0]!.label).toBe('Bộ phận');
  });
});
