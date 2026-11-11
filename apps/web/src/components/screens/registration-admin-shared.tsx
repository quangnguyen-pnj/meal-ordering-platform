'use client';

import type { RegistrationGridView, RegistrationUnitOption } from '@/lib/api-types';
import { useQuery } from '@tanstack/react-query';
import { FilterField } from '@/components/filter-field';
import { ResponsiveSelect } from '@/components/responsive-select';
import { api } from '@/lib/api';
import { t } from '@/messages';

export interface UnitsResponse {
  items: RegistrationUnitOption[];
  /** The current week (Monday) by server time. */
  currentWeekStart: string;
  /** The coming week by server time. */
  upcomingWeekStart: string;
}

/** Org nodes in the caller's scope (registration.onBehalf), shared by the grid and the forms. */
export function useRegistrationUnits() {
  return useQuery({
    queryKey: ['/admin/registrations/units'],
    queryFn: () => api.get<UnitsResponse>('/admin/registrations/units'),
  });
}

export function useRegistrationGrid(unitId: string, weekStart: string | null, canteenId: string) {
  return useQuery({
    queryKey: ['/admin/registrations/grid', unitId, weekStart, canteenId],
    queryFn: () =>
      api.get<RegistrationGridView>(
        `/admin/registrations/units/${encodeURIComponent(unitId)}/grid?weekStart=${weekStart}` +
          (canteenId ? `&canteenId=${encodeURIComponent(canteenId)}` : ''),
      ),
    enabled: !!unitId && !!weekStart,
    // Another unit, week or canteen keeps the last grid on screen (callers dim it and lock it)
    // instead of flashing a skeleton; with no unit picked there is nothing to keep.
    placeholderData: (prev) => (unitId && weekStart ? prev : undefined),
  });
}

export function UnitPicker({
  id,
  units,
  value,
  onChange,
}: {
  id: string;
  units: readonly RegistrationUnitOption[];
  value: string;
  onChange: (id: string) => void;
}) {
  return (
    <FilterField label={t('registrations.unit')} htmlFor={id} className="min-w-0 sm:max-w-md">
      <ResponsiveSelect
        id={id}
        value={value}
        onValueChange={onChange}
        placeholder={t('registrations.chooseUnit')}
        options={units.map((u) => ({
          value: u.id,
          label: t('registrations.unitOption', { path: u.pathName, count: u.employeeCount }),
        }))}
      />
    </FilterField>
  );
}

/** Only shown when the employees below the node eat in more than one canteen. */
export function CanteenPicker({
  id,
  grid,
  disabled,
  onChange,
}: {
  id: string;
  grid: RegistrationGridView | undefined;
  disabled?: boolean;
  onChange: (id: string) => void;
}) {
  if (!grid || grid.canteens.length < 2) return null;
  return (
    <FilterField label={t('registrations.canteen')} htmlFor={id} className="sm:w-56">
      <ResponsiveSelect
        id={id}
        value={grid.canteenId ?? ''}
        disabled={disabled}
        onValueChange={onChange}
        options={grid.canteens.map((c) => ({ value: c.id, label: c.name }))}
      />
    </FilterField>
  );
}
