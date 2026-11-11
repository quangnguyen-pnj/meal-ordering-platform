'use client';

import type { OrgFilterOption } from '@/lib/api-types';
import { FilterField } from '@/components/filter-field';
import { ResponsiveSelect } from '@/components/responsive-select';
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
        <FilterField
          key={depth}
          label={level.label || t('common.orgFilter')}
          htmlFor={`${id}-${depth}`}
          className="sm:w-52"
        >
          <ResponsiveSelect
            id={`${id}-${depth}`}
            value={level.value}
            onValueChange={(v) => onChange(orgFilterChange(levels, depth, v))}
            options={[
              { value: '', label: t('common.orgLevelAll', { level: level.label.toLowerCase() }) },
              ...level.options.map((o) => ({ value: o.id, label: o.name })),
            ]}
          />
        </FilterField>
      ))}
    </>
  );
}
