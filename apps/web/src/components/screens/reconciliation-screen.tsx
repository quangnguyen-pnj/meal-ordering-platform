'use client';

import { addDays } from '@meal/shared';
import { Download, Lock, Plus, Save } from 'lucide-react';
import type {
  AdjustmentCreated,
  AdjustmentsView,
  EmployeeDayView,
  FinalizeResult,
  ForecastWeekView,
  OrgFilterOption,
  ReconciliationView,
  TodayCase,
} from '@/lib/api-types';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { toast } from 'sonner';
import { AppDialog } from '@/components/app-dialog';
import { useConfirm } from '@/components/confirm-dialog';
import { EmployeePicker, type PickedEmployee } from '@/components/employee-picker';
import { OrgLevelFilter } from '@/components/org-level-filter';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import { DatePicker } from '@/components/ui/date-picker';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { LoadingRows, Refreshing } from '@/components/ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { WeekNav } from '@/components/week-nav';
import { api, ApiError } from '@/lib/api';
import { BANNERS_KEY } from '@/lib/banners';
import { apiErrorText } from '@/lib/errors';
import { formatDate, formatVnTime, vnDateOf } from '@/lib/utils';
import { t } from '@/messages';
import { SyncPanel } from './admin-today-screen';
import { weekdayName } from './day-meals';

const KEY = '/admin/reconciliation';
const URGENT: readonly TodayCase[] = [
  'PROVISIONALLY_CANCELLED',
  'CANCELLED_ABSENT',
  'LATE_DMP_CONFIRMATION',
  'LATE_CHECK_IN',
];

type Tab = 'meals' | 'exceptions' | 'forecast' | 'adjustments';

export interface ReconciliationPermissions {
  /** day.finalize (BR-43) */
  finalize: boolean;
  /** export.finalAndForecast (BR-44) */
  export: boolean;
  /** adjustment.manage (BR-46) */
  adjust: boolean;
  /** sync.manage (BR-34) */
  sync: boolean;
}

const signed = (n: number) => (n > 0 ? `+${n}` : String(n));

/**
 * /admin/reconciliation (plan 4.1, P4): summary cards and the per-meal comparison with the
 * forecast (BR-40, BR-42), the cases to review (BR-41), the attendance sync, finalization (BR-43),
 * the final and weekly forecast files (BR-44) and adjustments after finalization (BR-46). Dept
 * admins see their units, read-only.
 */
export function ReconciliationScreen({ can }: { can: ReconciliationPermissions }) {
  const qc = useQueryClient();
  const [date, setDate] = useState('');
  const [orgUnitId, setOrgUnitId] = useState('');
  const [tab, setTab] = useState<Tab>('meals');
  const [confirm, confirmDialog] = useConfirm();

  const query = useQuery({
    queryKey: [KEY, date, orgUnitId],
    queryFn: () => {
      const params = new URLSearchParams();
      if (date) params.set('date', date);
      if (orgUnitId) params.set('orgUnitId', orgUnitId);
      return api.get<ReconciliationView>(`/admin/reconciliation${params.size ? `?${params}` : ''}`);
    },
    placeholderData: keepPreviousData,
  });
  const orgUnits = useQuery({
    queryKey: ['/admin/registrations/org-units'],
    queryFn: () => api.get<{ items: OrgFilterOption[] }>('/admin/registrations/org-units'),
  });
  const view = query.data;
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: [KEY] });
    void qc.invalidateQueries({ queryKey: ['/admin/adjustments'] });
    void qc.invalidateQueries({ queryKey: BANNERS_KEY });
  };

  const finalize = useMutation({
    mutationFn: (day: string) =>
      api.post<FinalizeResult>('/admin/reconciliation/finalize', { date: day }),
    onSuccess: (res) => {
      toast.success(
        t('reconciliation.finalized', { date: formatDate(res.date), count: res.meals }),
      );
      refresh();
    },
  });
  const exportFinal = useMutation({
    mutationFn: (day: string) =>
      api.download(`/admin/reconciliation/export?date=${day}`, `chot-so-${day}.xlsx`),
  });

  const askFinalize = async (v: ReconciliationView) => {
    const ok = await confirm({
      title: t('reconciliation.finalizeTitle', { date: formatDate(v.date) }),
      description: [
        t('reconciliation.finalizeDescription', {
          deadline: formatVnTime(v.adjustmentDeadlineAt),
        }),
        v.cutoffHeld
          ? t('reconciliation.finalizeHeld', { cutoff: formatVnTime(v.attendance.cutoffAt) })
          : '',
      ]
        .filter(Boolean)
        .join(' '),
      confirmLabel: t('reconciliation.finalize'),
    });
    if (ok) finalize.mutate(v.date);
  };

  const tabs: Tab[] = ['meals', 'exceptions'];
  if (can.export) tabs.push('forecast');
  if (can.adjust && view?.closed) tabs.push('adjustments');
  const shownTab = tabs.includes(tab) ? tab : 'meals';

  return (
    <Card variant="page">
      {confirmDialog}
      <CardHeader>
        <CardTitle>{t('reconciliation.title')}</CardTitle>
        <CardDescription>
          {view ? `${weekdayName(view.date)} ${formatDate(view.date)} · ` : ''}
          {t('reconciliation.intro')}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        <div className="flex flex-wrap items-end gap-4">
          <div className="flex w-full flex-col gap-2 sm:w-44">
            <Label htmlFor="recon-date">{t('reconciliation.date')}</Label>
            <DatePicker
              id="recon-date"
              value={date || view?.date || ''}
              onChange={setDate}
              // Only today and the past: "today" is the server's clock, never the browser's.
              max={view ? vnDateOf(view.now) : undefined}
              placeholder={t('adminToday.datePlaceholder')}
            />
          </div>
          <OrgLevelFilter
            id="recon-org"
            options={orgUnits.data?.items ?? []}
            value={orgUnitId}
            onChange={setOrgUnitId}
          />
        </div>

        {query.error ? <Alert variant="destructive">{apiErrorText(query.error)}</Alert> : null}
        {query.isLoading ? <LoadingRows rows={4} /> : null}

        {view ? (
          <Refreshing busy={query.isPlaceholderData} className="flex flex-col gap-6">
            {!view.servingDay ? <Alert>{t('reconciliation.notServing')}</Alert> : null}

            <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <Stat label={t('reconciliation.summary.forecast')} value={view.summary.forecast} />
              <Stat label={t('reconciliation.summary.actual')} value={view.summary.actual} />
              <Stat
                label={t('reconciliation.summary.difference')}
                value={signed(view.summary.difference)}
              />
              <Stat label={t('reconciliation.summary.cases')} value={view.summary.cases} />
              {view.closed ? (
                <>
                  <Stat
                    label={t('reconciliation.summary.final')}
                    value={view.summary.finalCount ?? 0}
                  />
                  <Stat
                    label={t('reconciliation.summary.adjustment')}
                    value={signed(view.summary.adjustment ?? 0)}
                  />
                  <Stat
                    label={t('reconciliation.summary.effective')}
                    value={view.summary.effective ?? 0}
                  />
                </>
              ) : null}
            </dl>

            <section className="flex flex-col gap-2 rounded-lg bg-card p-4 text-sm shadow-block">
              {view.closed ? (
                <p className="flex items-center gap-2 font-medium">
                  <Lock className="h-4 w-4" aria-hidden />
                  {view.closed.autoFinalized
                    ? t('reconciliation.closedAuto', { at: formatVnTime(view.closed.finalizedAt) })
                    : t('reconciliation.closed', {
                        at: formatVnTime(view.closed.finalizedAt),
                        name: view.closed.finalizedByName ?? '',
                      })}
                </p>
              ) : view.servingDay ? (
                <p className="text-muted-foreground">
                  {t('reconciliation.finalizeOpensAt', {
                    at: formatVnTime(view.finalizeOpensAt),
                    auto: formatVnTime(view.autoFinalizeAt),
                  })}
                </p>
              ) : null}
              {view.closed ? (
                <p className="text-muted-foreground">
                  {view.canAdjust
                    ? t('reconciliation.adjustUntil', {
                        deadline: formatVnTime(view.adjustmentDeadlineAt),
                      })
                    : t('reconciliation.adjustClosed', {
                        deadline: formatVnTime(view.adjustmentDeadlineAt),
                      })}
                </p>
              ) : null}
              {finalize.error ? (
                <Alert variant="destructive">{apiErrorText(finalize.error)}</Alert>
              ) : null}
              {exportFinal.error ? (
                <Alert variant="destructive">{apiErrorText(exportFinal.error)}</Alert>
              ) : null}
              <div className="flex flex-wrap gap-2">
                {can.finalize && !view.closed && view.servingDay ? (
                  <Button
                    disabled={!view.canFinalize || finalize.isPending}
                    onClick={() => void askFinalize(view)}
                  >
                    <Lock className="h-4 w-4" aria-hidden />
                    {finalize.isPending
                      ? t('reconciliation.finalizing')
                      : t('reconciliation.finalize')}
                  </Button>
                ) : null}
                {can.export && view.closed ? (
                  <Button
                    variant="outline"
                    disabled={exportFinal.isPending}
                    onClick={() => exportFinal.mutate(view.date)}
                  >
                    <Download className="h-4 w-4" aria-hidden />
                    {t('reconciliation.exportFinal')}
                  </Button>
                ) : null}
              </div>
            </section>

            <SyncPanel status={view.attendance} canSync={can.sync} onDone={refresh} />

            <Tabs value={shownTab} onValueChange={(v) => setTab(v as Tab)}>
              <TabsList>
                <TabsTrigger value="meals">{t('reconciliation.tabs.meals')}</TabsTrigger>
                <TabsTrigger value="exceptions">
                  {t('reconciliation.tabs.exceptions', { count: view.exceptions.length })}
                </TabsTrigger>
                {tabs.includes('forecast') ? (
                  <TabsTrigger value="forecast">{t('reconciliation.tabs.forecast')}</TabsTrigger>
                ) : null}
                {tabs.includes('adjustments') ? (
                  <TabsTrigger value="adjustments">
                    {t('reconciliation.tabs.adjustments')}
                  </TabsTrigger>
                ) : null}
              </TabsList>
            </Tabs>

            {shownTab === 'meals' ? <MealsTable view={view} /> : null}
            {shownTab === 'exceptions' ? <ExceptionsTable view={view} /> : null}
            {shownTab === 'forecast' ? <ForecastTab date={view.date} /> : null}
            {shownTab === 'adjustments' ? (
              <AdjustmentsTab date={view.date} onChanged={refresh} />
            ) : null}
          </Refreshing>
        ) : null}
      </CardContent>
    </Card>
  );
}

function Stat({ label, value }: { label: string; value: number | string }) {
  return (
    <div className="rounded-lg bg-card p-4 shadow-block">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-xl font-semibold tabular-nums">{value}</dd>
    </div>
  );
}

/** BR-40 / BR-42: forecast, actual, difference and status per meal, with the deviation warning. */
function MealsTable({ view }: { view: ReconciliationView }) {
  if (view.meals.length === 0) {
    return <p className="text-sm text-muted-foreground">{t('reconciliation.noMeals')}</p>;
  }
  const closed = view.closed !== null;
  return (
    <Table className="min-w-[40rem]">
      <TableHeader>
        <TableRow>
          <TableHead>{t('reconciliation.columns.meal')}</TableHead>
          <TableHead className="text-right">{t('reconciliation.columns.forecast')}</TableHead>
          <TableHead className="text-right">{t('reconciliation.columns.actual')}</TableHead>
          <TableHead className="text-right">{t('reconciliation.columns.difference')}</TableHead>
          <TableHead>{t('reconciliation.columns.status')}</TableHead>
          {closed ? (
            <>
              <TableHead className="text-right">{t('reconciliation.columns.adjustment')}</TableHead>
              <TableHead className="text-right">{t('reconciliation.columns.effective')}</TableHead>
            </>
          ) : null}
        </TableRow>
      </TableHeader>
      <TableBody>
        {view.meals.map((m) => (
          <TableRow key={m.menuItemId}>
            <TableCell>
              <span className="block">{m.displayName}</span>
              <span className="text-xs text-muted-foreground">{m.canteenName}</span>
            </TableCell>
            <TableCell className="text-right tabular-nums">{m.forecast}</TableCell>
            <TableCell className="text-right tabular-nums">{m.actual}</TableCell>
            <TableCell className="text-right tabular-nums">{signed(m.difference)}</TableCell>
            <TableCell>
              <div className="flex flex-col items-start gap-1">
                <Badge variant={m.status === 'MATCHED' ? 'success' : 'outline'}>
                  {t(`reconciliation.status.${m.status}`)}
                </Badge>
                {m.deviates ? (
                  <Badge variant="warning">
                    {m.deviationPct === null
                      ? t('reconciliation.deviationNoForecast')
                      : t('reconciliation.deviation', {
                          pct: signed(m.deviationPct),
                          threshold: view.deviationThresholdPct,
                        })}
                  </Badge>
                ) : null}
              </div>
            </TableCell>
            {closed ? (
              <>
                <TableCell className="text-right tabular-nums">
                  {signed(m.adjustment ?? 0)}
                </TableCell>
                <TableCell className="text-right font-medium tabular-nums">
                  {m.effective ?? 0}
                </TableCell>
              </>
            ) : null}
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

/** BR-41: code, name, unit, forecast meal, actual meal and case type. */
function ExceptionsTable({ view }: { view: ReconciliationView }) {
  if (view.exceptions.length === 0) {
    return <p className="text-sm text-muted-foreground">{t('reconciliation.noExceptions')}</p>;
  }
  const meals = (lines: { displayName: string; quantity: number }[]) =>
    lines
      .filter((l) => l.quantity > 0)
      .map((l) => `${l.quantity} × ${l.displayName}`)
      .join(', ') || t('common.none');
  return (
    <Table className="min-w-[44rem]">
      <TableHeader>
        <TableRow>
          <TableHead>{t('common.code')}</TableHead>
          <TableHead>{t('common.name')}</TableHead>
          <TableHead>{t('reconciliation.columns.unit')}</TableHead>
          <TableHead>{t('reconciliation.columns.forecastMeals')}</TableHead>
          <TableHead>{t('reconciliation.columns.actualMeals')}</TableHead>
          <TableHead>{t('reconciliation.columns.cases')}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {view.exceptions.map((r) => (
          <TableRow key={r.employeeCode}>
            <TableCell className="font-mono text-xs">{r.employeeCode}</TableCell>
            <TableCell>{r.name}</TableCell>
            <TableCell className="text-xs text-muted-foreground">{r.orgPath}</TableCell>
            <TableCell className="text-sm">
              {meals(r.lines.map((l) => ({ displayName: l.displayName, quantity: l.forecast })))}
            </TableCell>
            <TableCell className={`text-sm ${r.mealsCount ? '' : 'line-through'}`}>
              {meals(r.lines)}
            </TableCell>
            <TableCell>
              <div className="flex flex-wrap gap-1">
                {r.cases.map((c) => (
                  <Badge key={c} variant={URGENT.includes(c) ? 'destructive' : 'warning'}>
                    {t(`adminToday.cases.${c}`)}
                  </Badge>
                ))}
              </div>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function mondayOf(date: string): string {
  const day = new Date(`${date}T00:00:00Z`).getUTCDay();
  return addDays(date, -((day + 6) % 7));
}

/** BR-07 / BR-44: the weekly forecast per day and its Excel file. */
function ForecastTab({ date }: { date: string }) {
  const [weekStart, setWeekStart] = useState(() => mondayOf(date));
  const query = useQuery({
    queryKey: ['/admin/reconciliation/forecast', weekStart],
    queryFn: () =>
      api.get<ForecastWeekView>(`/admin/reconciliation/forecast?weekStart=${weekStart}`),
    placeholderData: keepPreviousData,
  });
  const download = useMutation({
    mutationFn: () =>
      api.download(
        `/admin/reconciliation/forecast/export?weekStart=${weekStart}`,
        `du-tru-tuan-${weekStart}.xlsx`,
      ),
  });
  const view = query.data;
  return (
    <div className="flex flex-col gap-4">
      <WeekNav weekStart={weekStart} onChange={setWeekStart} homeWeekStart={mondayOf(date)} />
      {query.error ? <Alert variant="destructive">{apiErrorText(query.error)}</Alert> : null}
      {download.error ? <Alert variant="destructive">{apiErrorText(download.error)}</Alert> : null}
      {view ? (
        <Refreshing busy={query.isPlaceholderData} className="flex flex-col gap-4">
          {view.canteens.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t('reconciliation.forecast.none')}</p>
          ) : null}
          {view.canteens.map((c) => (
            <section key={c.canteenId} className="flex flex-col gap-2">
              <h3 className="font-medium">{c.canteenName}</h3>
              {c.closedAt === null ? (
                <p className="text-sm text-muted-foreground">
                  {t('reconciliation.forecast.notTaken')}
                </p>
              ) : (
                <>
                  <p className="text-xs text-muted-foreground">
                    {t('reconciliation.forecast.takenAt', { at: formatVnTime(c.closedAt) })}
                  </p>
                  <Table>
                    <TableBody>
                      {c.days.map((d) => (
                        <TableRow key={d.date}>
                          <TableCell className="whitespace-nowrap">
                            {weekdayName(d.date)} {formatDate(d.date)}
                          </TableCell>
                          <TableCell className="text-sm">
                            {d.meals.map((m) => `${m.quantity} × ${m.displayName}`).join(', ') ||
                              t('common.none')}
                          </TableCell>
                          <TableCell className="text-right font-medium tabular-nums">
                            {t('reconciliation.forecast.total', { count: d.total })}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </>
              )}
            </section>
          ))}
          {view.exportable ? (
            <div>
              <Button
                variant="outline"
                disabled={download.isPending}
                onClick={() => download.mutate()}
              >
                <Download className="h-4 w-4" aria-hidden />
                {t('reconciliation.forecast.export')}
              </Button>
            </div>
          ) : null}
        </Refreshing>
      ) : null}
    </div>
  );
}

/** BR-46: the day's adjustments, their files, and the form to add one. */
function AdjustmentsTab({ date, onChanged }: { date: string; onChanged: () => void }) {
  const [open, setOpen] = useState(false);
  const query = useQuery({
    queryKey: ['/admin/adjustments', date],
    queryFn: () => api.get<AdjustmentsView>(`/admin/adjustments?date=${date}`),
  });
  const download = useMutation({
    mutationFn: (a: { id: string; number: number }) =>
      api.download(`/admin/adjustments/${a.id}/export`, `dieu-chinh-${date}-${a.number}.xlsx`),
  });
  const view = query.data;
  return (
    <div className="flex flex-col gap-4">
      {query.error ? <Alert variant="destructive">{apiErrorText(query.error)}</Alert> : null}
      {download.error ? <Alert variant="destructive">{apiErrorText(download.error)}</Alert> : null}
      {view?.canAdjust ? (
        <div>
          <Button onClick={() => setOpen(true)}>
            <Plus className="h-4 w-4" aria-hidden />
            {t('reconciliation.adjustments.add')}
          </Button>
        </div>
      ) : null}
      {view && view.items.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t('reconciliation.adjustments.empty')}</p>
      ) : null}
      {view?.items.map((a) => (
        <section
          key={a.id}
          className="flex flex-col gap-2 rounded-lg bg-card p-4 text-sm shadow-block"
        >
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3 className="font-medium">
              {t('reconciliation.adjustments.number', { number: a.number })}
            </h3>
            <Button
              size="sm"
              variant="outline"
              disabled={download.isPending}
              onClick={() => download.mutate(a)}
            >
              <Download className="h-4 w-4" aria-hidden />
              {t('reconciliation.adjustments.file')}
            </Button>
          </div>
          <p className="text-muted-foreground">
            {t('reconciliation.adjustments.by', {
              name: a.createdByName,
              at: formatVnTime(a.createdAt),
            })}
            {a.catererConfirmed ? ` · ${t('reconciliation.adjustments.catererConfirmed')}` : ''}
          </p>
          <p>{a.reason}</p>
          <ul>
            {a.lines.map((l) => (
              <li key={`${l.employeeCode}-${l.menuItemId}`}>
                <span className="font-mono text-xs">{l.employeeCode}</span> {l.employeeName}:{' '}
                {signed(l.delta)} × {l.displayName}
              </li>
            ))}
          </ul>
          <p className="font-medium">
            {t('reconciliation.adjustments.totalDelta', { delta: signed(a.totalDelta) })}
          </p>
        </section>
      ))}
      <AdjustmentDialog
        open={open}
        date={date}
        onClose={() => setOpen(false)}
        onDone={(res) => {
          setOpen(false);
          toast.success(t('reconciliation.adjustments.saved', { number: res.number }));
          void query.refetch();
          onChanged();
        }}
      />
    </div>
  );
}

function adjustmentErrors(error: unknown): string[] {
  if (
    error instanceof ApiError &&
    error.code === 'VALIDATION_FAILED' &&
    Array.isArray(error.details)
  ) {
    const codes = (error.details as { code?: string }[])
      .map((d) => d.code)
      .filter((c): c is keyof typeof ERROR_KEYS => !!c && c in ERROR_KEYS);
    if (codes.length > 0) return [...new Set(codes)].map((c) => t(ERROR_KEYS[c]));
  }
  return [apiErrorText(error)];
}

const ERROR_KEYS = {
  NO_LINES: 'reconciliation.adjustments.errors.NO_LINES',
  REASON_REQUIRED: 'reconciliation.adjustments.errors.REASON_REQUIRED',
  CATERER_CONFIRMATION_REQUIRED: 'reconciliation.adjustments.errors.CATERER_CONFIRMATION_REQUIRED',
  DELTA_ZERO: 'reconciliation.adjustments.errors.DELTA_ZERO',
  DELTA_NOT_INTEGER: 'reconciliation.adjustments.errors.DELTA_NOT_INTEGER',
  DUPLICATE_LINE: 'reconciliation.adjustments.errors.DUPLICATE_LINE',
  EMPLOYEE_UNKNOWN: 'reconciliation.adjustments.errors.EMPLOYEE_UNKNOWN',
  ITEM_NOT_ON_MENU: 'reconciliation.adjustments.errors.ITEM_NOT_ON_MENU',
  BELOW_ZERO: 'reconciliation.adjustments.errors.BELOW_ZERO',
} as const;

/**
 * BR-46: one employee's adjustment: a signed change per meal, a required reason and, when any
 * meal is added, the caterer's confirmation.
 */
function AdjustmentDialog({
  open,
  date,
  onClose,
  onDone,
}: {
  open: boolean;
  date: string;
  onClose: () => void;
  onDone: (res: AdjustmentCreated) => void;
}) {
  const [employee, setEmployee] = useState<PickedEmployee | null>(null);
  const [deltas, setDeltas] = useState<Record<string, string>>({});
  const [reason, setReason] = useState('');
  const [caterer, setCaterer] = useState(false);
  const day = useQuery({
    queryKey: ['/admin/adjustments/employees', employee?.employeeCode, date],
    queryFn: () =>
      api.get<EmployeeDayView>(
        `/admin/adjustments/employees/${encodeURIComponent(employee!.employeeCode)}?date=${date}`,
      ),
    enabled: open && employee !== null,
  });
  const lines = Object.entries(deltas)
    .map(([menuItemId, v]) => ({ menuItemId, delta: Number(v) }))
    .filter((l) => v(l.delta));
  const adds = lines.some((l) => l.delta > 0);
  const save = useMutation({
    mutationFn: () =>
      api.post<AdjustmentCreated>('/admin/adjustments', {
        date,
        reason: reason.trim(),
        catererConfirmed: caterer,
        lines: lines.map((l) => ({ ...l, employeeCode: employee!.employeeCode })),
      }),
    onSuccess: (res) => {
      reset();
      onDone(res);
    },
  });
  function reset() {
    setEmployee(null);
    setDeltas({});
    setReason('');
    setCaterer(false);
    save.reset();
  }
  const close = () => {
    reset();
    onClose();
  };
  return (
    <AppDialog
      open={open}
      onClose={close}
      title={t('reconciliation.adjustments.title')}
      description={t('reconciliation.adjustments.description')}
      className="sm:max-w-xl"
      footer={
        <>
          <Button variant="outline" onClick={close}>
            {t('common.cancel')}
          </Button>
          <Button
            form="adjustment-form"
            type="submit"
            disabled={save.isPending || lines.length === 0 || !reason.trim() || (adds && !caterer)}
          >
            <Save className="h-4 w-4" aria-hidden />
            {save.isPending ? t('common.saving') : t('reconciliation.adjustments.save')}
          </Button>
        </>
      }
    >
      <form
        id="adjustment-form"
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (employee) save.mutate();
        }}
      >
        {save.error
          ? adjustmentErrors(save.error).map((m) => (
              <Alert key={m} variant="destructive">
                {m}
              </Alert>
            ))
          : null}
        <div className="flex flex-col gap-2">
          <Label htmlFor="adjustment-employee">{t('reconciliation.adjustments.employee')}</Label>
          <EmployeePicker
            id="adjustment-employee"
            value={employee}
            onChange={(e) => {
              setEmployee(e);
              setDeltas({});
            }}
          />
        </div>
        {day.error ? <Alert variant="destructive">{apiErrorText(day.error)}</Alert> : null}
        {day.data ? (
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1 text-sm font-medium">
              {t('reconciliation.adjustments.meals')}
            </legend>
            {day.data.canteenName === null ? (
              <p className="text-xs text-muted-foreground">
                {t('reconciliation.adjustments.noCanteen')}
              </p>
            ) : null}
            {day.data.meals.map((m) => (
              <div key={m.menuItemId} className="flex items-center justify-between gap-3">
                <Label htmlFor={`delta-${m.menuItemId}`} className="flex flex-col">
                  <span>{m.displayName}</span>
                  <span className="text-xs text-muted-foreground">
                    {t('reconciliation.adjustments.current', { count: m.effective })}
                  </span>
                </Label>
                <Input
                  id={`delta-${m.menuItemId}`}
                  type="number"
                  inputMode="numeric"
                  step={1}
                  min={-m.effective}
                  className="w-24 text-right"
                  aria-label={`${t('reconciliation.adjustments.delta')} ${m.displayName}`}
                  value={deltas[m.menuItemId] ?? ''}
                  onChange={(e) =>
                    setDeltas((prev) => ({ ...prev, [m.menuItemId]: e.target.value }))
                  }
                />
              </div>
            ))}
          </fieldset>
        ) : null}
        <div className="flex flex-col gap-2">
          <Label htmlFor="adjustment-reason">{t('reconciliation.adjustments.reason')}</Label>
          <Input
            id="adjustment-reason"
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </div>
        <div className="flex items-start gap-2">
          <Checkbox
            id="adjustment-caterer"
            checked={caterer}
            onCheckedChange={(c) => setCaterer(c === true)}
          />
          <Label htmlFor="adjustment-caterer" className="flex flex-col">
            <span>{t('reconciliation.adjustments.caterer')}</span>
            {adds ? (
              <span className="text-xs text-muted-foreground">
                {t('reconciliation.adjustments.catererRequired')}
              </span>
            ) : null}
          </Label>
        </div>
      </form>
    </AppDialog>
  );
}

/** A non-zero whole number. */
function v(n: number): boolean {
  return Number.isInteger(n) && n !== 0;
}
