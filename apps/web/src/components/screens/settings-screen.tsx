'use client';

import { SETTING_DEFINITIONS, type SettingKey, type WeekdayTime } from '@meal/shared';
import type { SettingView } from '@/lib/api-types';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { toast } from 'sonner';
import { ResponsiveSelect } from '@/components/responsive-select';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { LoadingRows } from '@/components/ui/skeleton';
import { TimeInput } from '@/components/ui/time-input';
import { api } from '@/lib/api';
import { apiErrorText } from '@/lib/errors';
import { cn } from '@/lib/utils';
import { t, vi } from '@/messages';

type Editor = 'weekdayTime' | 'time' | 'number' | 'days';

const EDITOR: Record<SettingKey, Editor> = {
  'registration.close': 'weekdayTime',
  'registration.latePublishHours': 'number',
  'menu.publishWarnAt': 'weekdayTime',
  servingDays: 'days',
  'day.lockTime': 'time',
  'attendance.pollStart': 'time',
  'attendance.pollIntervalMin': 'number',
  'attendance.cutoff': 'time',
  'attendance.safetyMinPresentPct': 'number',
  'reconciliation.dishDeviationPct': 'number',
  'close.time': 'time',
  'close.reminderAt': 'time',
  'close.adjustmentDeadline': 'time',
  'delivery.tolerancePct': 'number',
  'delivery.confirmDeadline': 'time',
  'pin.maxAttempts': 'number',
  'pin.lockMinutes': 'number',
};

const GROUPS: Array<{ key: keyof typeof vi.settings.groups; keys: SettingKey[] }> = [
  {
    key: 'registration',
    keys: ['registration.close', 'registration.latePublishHours', 'menu.publishWarnAt'],
  },
  { key: 'day', keys: ['servingDays', 'day.lockTime'] },
  {
    key: 'attendance',
    keys: [
      'attendance.pollStart',
      'attendance.pollIntervalMin',
      'attendance.cutoff',
      'attendance.safetyMinPresentPct',
    ],
  },
  {
    key: 'close',
    keys: [
      'reconciliation.dishDeviationPct',
      'close.time',
      'close.reminderAt',
      'close.adjustmentDeadline',
      'delivery.tolerancePct',
      'delivery.confirmDeadline',
    ],
  },
  { key: 'pin', keys: ['pin.maxAttempts', 'pin.lockMinutes'] },
];

const weekdayName = (n: number) => vi.weekdays[n as keyof typeof vi.weekdays];

export function describeSetting(key: SettingKey, value: unknown): string {
  switch (EDITOR[key]) {
    case 'weekdayTime': {
      const v = value as WeekdayTime;
      return t('settings.weekdayTime', { weekday: weekdayName(v.weekday), time: v.time });
    }
    case 'days':
      return (value as number[]).map(weekdayName).join(', ');
    default:
      return String(value);
  }
}

type Drafts = Partial<Record<SettingKey, unknown>>;

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

/** /admin/settings: plan 6.4 keys plus the caterer and unit price (plan section 3). */
export function SettingsScreen() {
  const qc = useQueryClient();
  const list = useQuery({
    queryKey: ['/admin/settings'],
    queryFn: () => api.get<{ items: SettingView[] }>('/admin/settings'),
  });
  // Only edited keys live here; everything else shows the stored value.
  const [drafts, setDrafts] = useState<Drafts>({});
  const [errors, setErrors] = useState<Partial<Record<SettingKey, string>>>({});
  const byKey = new Map((list.data?.items ?? []).map((s) => [s.key as SettingKey, s]));

  const changedKeys = (Object.keys(drafts) as SettingKey[]).filter((k) => {
    const stored = byKey.get(k);
    return stored !== undefined && !same(drafts[k], stored.value);
  });
  const invalidKeys = changedKeys.filter(
    (k) => !SETTING_DEFINITIONS[k].schema.safeParse(drafts[k]).success,
  );

  useEffect(() => {
    if (changedKeys.length === 0) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [changedKeys.length]);

  // One PUT per changed key: each is validated and audited on its own by the API.
  const save = useMutation({
    mutationFn: async (keys: SettingKey[]) => {
      const failed: Partial<Record<SettingKey, string>> = {};
      const saved: SettingKey[] = [];
      for (const key of keys) {
        try {
          await api.put<SettingView>(`/admin/settings/${key}`, { value: drafts[key] });
          saved.push(key);
        } catch (error) {
          failed[key] = apiErrorText(error);
        }
      }
      return { saved, failed };
    },
    onSuccess: ({ saved, failed }) => {
      setErrors(failed);
      setDrafts((d) => {
        const next = { ...d };
        for (const k of saved) delete next[k];
        return next;
      });
      if (Object.keys(failed).length === 0) {
        toast.success(t('settings.savedCount', { count: saved.length }));
      } else {
        toast.error(
          t('settings.partialSave', { saved: saved.length, failed: Object.keys(failed).length }),
        );
      }
      void qc.invalidateQueries({ queryKey: ['/admin/settings'] });
    },
  });

  if (list.error) return <Alert variant="destructive">{apiErrorText(list.error)}</Alert>;

  const setValue = (key: SettingKey, value: unknown) => {
    setDrafts((d) => ({ ...d, [key]: value }));
  };

  return (
    <div className="flex flex-col gap-5">
      <Card>
        <CardHeader>
          <CardTitle>{t('settings.title')}</CardTitle>
          <CardDescription>{t('settings.intro')}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-6">
          {list.isLoading ? <LoadingRows rows={6} /> : null}
          {GROUPS.map((group) => (
            <section key={group.key} className="flex flex-col gap-3">
              <h2 className="text-sm font-semibold uppercase tracking-wide text-muted-foreground">
                {vi.settings.groups[group.key]}
              </h2>
              {group.keys.map((key) => {
                const setting = byKey.get(key);
                if (!setting) return null;
                const value = key in drafts ? drafts[key] : setting.value;
                return (
                  <SettingRow
                    key={key}
                    settingKey={key}
                    setting={setting}
                    value={value}
                    error={errors[key]}
                    onChange={(v) => setValue(key, v)}
                  />
                );
              })}
            </section>
          ))}
        </CardContent>
      </Card>

      {changedKeys.length > 0 ? (
        <div
          role="region"
          aria-label={t('settings.saveBar')}
          className="sticky bottom-0 z-10 -mx-4 flex flex-wrap items-center justify-between gap-3 bg-card px-4 py-3 shadow-[0_-6px_16px_-6px_rgb(41_37_36/0.12)] md:-mx-8 md:px-8"
        >
          <p className="text-sm" role="status">
            {invalidKeys.length > 0
              ? t('settings.invalidCount', { count: invalidKeys.length })
              : t('settings.changedCount', { count: changedKeys.length })}
          </p>
          <div className="flex gap-2">
            <Button
              variant="outline"
              disabled={save.isPending}
              onClick={() => {
                setDrafts({});
                setErrors({});
              }}
            >
              {t('settings.discard')}
            </Button>
            <Button
              disabled={save.isPending || invalidKeys.length > 0}
              onClick={() => save.mutate(changedKeys)}
            >
              {save.isPending
                ? t('common.saving')
                : t('settings.saveAll', { count: changedKeys.length })}
            </Button>
          </div>
        </div>
      ) : null}

      <SupplierCard />
    </div>
  );
}

function SettingRow({
  settingKey,
  setting,
  value,
  error,
  onChange,
}: {
  settingKey: SettingKey;
  setting: SettingView;
  value: unknown;
  error?: string;
  onChange: (value: unknown) => void;
}) {
  const editor = EDITOR[settingKey];
  const valid = SETTING_DEFINITIONS[settingKey].schema.safeParse(value).success;
  const changed = !same(value, setting.value);
  const isDefault = same(value, setting.defaultValue);
  const id = `setting-${settingKey}`;

  return (
    <div
      className={cn(
        'flex flex-col gap-3 rounded-lg bg-card p-4 shadow-block transition-[box-shadow,background-color] md:flex-row md:items-start md:justify-between md:gap-6',
        changed && 'bg-primary/5 ring-1 ring-primary/40',
      )}
    >
      <div className="flex flex-col gap-1 md:w-1/2">
        <Label htmlFor={id}>{vi.settings.keys[settingKey]}</Label>
        <p className="text-xs text-muted-foreground">
          {t('settings.default', { value: describeSetting(settingKey, setting.defaultValue) })}
          {changed ? (
            <span className="ml-2 font-medium text-primary">
              {t('settings.was', { value: describeSetting(settingKey, setting.value) })}
            </span>
          ) : null}
        </p>
      </div>
      <div className="flex flex-col gap-2 md:items-end">
        <div className="flex flex-wrap items-center gap-2">
          {editor === 'number' ? (
            <Input
              id={id}
              type="number"
              className="w-28"
              aria-invalid={!valid || undefined}
              value={String(value)}
              onChange={(e) => onChange(e.target.value === '' ? '' : Number(e.target.value))}
            />
          ) : editor === 'time' ? (
            <TimeInput
              id={id}
              aria-invalid={!valid || undefined}
              value={String(value)}
              onChange={onChange}
            />
          ) : editor === 'weekdayTime' ? (
            <>
              <ResponsiveSelect
                id={id}
                className="w-36"
                value={String((value as WeekdayTime).weekday)}
                onValueChange={(v) => onChange({ ...(value as WeekdayTime), weekday: Number(v) })}
                options={[1, 2, 3, 4, 5, 6, 7].map((d) => ({
                  value: String(d),
                  label: weekdayName(d),
                }))}
              />
              <TimeInput
                aria-label={`${vi.settings.keys[settingKey]} (${t('settings.time')})`}
                aria-invalid={!valid || undefined}
                value={(value as WeekdayTime).time}
                onChange={(time) => onChange({ ...(value as WeekdayTime), time })}
              />
            </>
          ) : (
            <div
              id={id}
              role="group"
              aria-label={vi.settings.keys[settingKey]}
              className="flex flex-wrap gap-x-3"
            >
              {[1, 2, 3, 4, 5, 6, 7].map((d) => (
                <label key={d} className="flex min-h-11 items-center gap-2 text-sm">
                  <Checkbox
                    checked={(value as number[]).includes(d)}
                    onCheckedChange={(checked) => {
                      const days = new Set(value as number[]);
                      if (checked === true) days.add(d);
                      else days.delete(d);
                      onChange([...days].sort());
                    }}
                  />
                  {weekdayName(d)}
                </label>
              ))}
            </div>
          )}
          {!isDefault ? (
            <Button variant="ghost" size="sm" onClick={() => onChange(setting.defaultValue)}>
              {t('settings.useDefault')}
            </Button>
          ) : null}
        </div>
        {!valid ? (
          <p className="text-sm text-destructive">{t('errors.VALIDATION_FAILED')}</p>
        ) : null}
        {error ? <p className="text-sm text-destructive">{error}</p> : null}
      </div>
    </div>
  );
}

function SupplierCard() {
  const qc = useQueryClient();
  const supplier = useQuery({
    queryKey: ['/admin/supplier'],
    queryFn: () => api.get<{ id: string; name: string; unitPrice: number }>('/admin/supplier'),
  });
  const [draft, setDraft] = useState<{ name: string; unitPrice: string } | null>(null);
  const save = useMutation({
    mutationFn: (d: { name: string; unitPrice: string }) =>
      api.put('/admin/supplier', { name: d.name, unitPrice: Number(d.unitPrice) }),
    onSuccess: () => {
      setDraft(null);
      toast.success(t('common.saved'));
      void qc.invalidateQueries({ queryKey: ['/admin/supplier'] });
    },
  });
  const current =
    draft ??
    (supplier.data
      ? { name: supplier.data.name, unitPrice: String(supplier.data.unitPrice) }
      : null);

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-lg">{t('settings.supplierTitle')}</CardTitle>
      </CardHeader>
      <CardContent>
        {current ? (
          <form
            className="flex flex-wrap items-end gap-4"
            onSubmit={(e) => {
              e.preventDefault();
              save.mutate(current);
            }}
          >
            <div className="flex min-w-72 flex-1 flex-col gap-2">
              <Label htmlFor="supplier-name">{t('settings.supplierName')}</Label>
              <Input
                id="supplier-name"
                required
                value={current.name}
                onChange={(e) => setDraft({ ...current, name: e.target.value })}
              />
            </div>
            <div className="flex w-48 flex-col gap-2">
              <Label htmlFor="supplier-price">{t('settings.unitPrice')}</Label>
              <Input
                id="supplier-price"
                type="number"
                min={0}
                step={500}
                required
                value={current.unitPrice}
                onChange={(e) => setDraft({ ...current, unitPrice: e.target.value })}
              />
            </div>
            <Button type="submit" disabled={!draft || save.isPending}>
              {save.isPending ? t('common.saving') : t('common.save')}
            </Button>
          </form>
        ) : null}
        {(save.error ?? supplier.error) ? (
          <Alert variant="destructive" className="mt-3">
            {apiErrorText(save.error ?? supplier.error)}
          </Alert>
        ) : null}
      </CardContent>
    </Card>
  );
}
