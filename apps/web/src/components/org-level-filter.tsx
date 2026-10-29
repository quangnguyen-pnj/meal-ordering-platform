'use client';

import type { OrgFilterOption } from '@/lib/api-types';
import { ResponsiveSelect } from '@/components/responsive-select';
import { Label } from '@/components/ui/label';
import { orgFilterChange, orgFilterLevels } from '@/lib/org-filter';
import { t } from '@/messages';

/** Cascading Khối / Trung tâm / Phòng ban selects; `value` is the deepest chosen node id. */
export function OrgLevelFilter({
  id,
  options,
  value,
  onChange,
}: {
  id: string;
  options: readonly OrgFilterOption[];
  value: string;
  onChange: (orgUnitId: string) => void;
}) {
  const levels = orgFilterLevels(options, value || null);
  return (
    <>
      {levels.map((level, depth) => (
        <div key={depth} className="flex w-full flex-col gap-2 sm:w-52">
          <Label htmlFor={`${id}-${depth}`}>{level.label || t('common.orgFilter')}</Label>
          <ResponsiveSelect
            id={`${id}-${depth}`}
            value={level.value}
            onValueChange={(v) => onChange(orgFilterChange(levels, depth, v))}
            options={[
              { value: '', label: t('common.orgLevelAll', { level: level.label.toLowerCase() }) },
              ...level.options.map((o) => ({ value: o.id, label: o.name })),
            ]}
          />
        </div>
      ))}
    </>
  );
}
