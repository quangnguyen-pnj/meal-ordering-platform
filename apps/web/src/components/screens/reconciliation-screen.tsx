'use client';

import {
  AlertTriangle,
  ArrowRight,
  Check,
  Download,
  ExternalLink,
  Lock,
  Plus,
  Save,
} from 'lucide-react';
import Link from 'next/link';
import type {
  AdjustmentCreated,
  AdjustmentsView,
  EmployeeDayView,
  FinalizeResult,
  OrgFilterOption,
  ReconciliationMeal,
  ReconciliationView,
  TodayCase,
  TodayRow,
} from '@/lib/api-types';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Fragment, useRef, useState } from 'react';
import { toast } from 'sonner';
import { AppDialog } from '@/components/app-dialog';
import { useConfirm } from '@/components/confirm-dialog';
import { DayNav } from '@/components/day-nav';
import { EmployeePicker, type PickedEmployee } from '@/components/employee-picker';
import { OrgLevelFilter } from '@/components/org-level-filter';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import { fieldSurface } from '@/components/ui/field';
import { Label } from '@/components/ui/label';
import { QuantityStepper } from '@/components/ui/quantity-stepper';
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
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { api, ApiError } from '@/lib/api';
import { BANNERS_KEY } from '@/lib/banners';
import { apiErrorText } from '@/lib/errors';
import { cn, formatDate, formatVnTime, vnDateOf } from '@/lib/utils';
import { t } from '@/messages';
import { SyncPanel } from './admin-today-screen';

const KEY = '/admin/reconciliation';

/**
 * How loud each case is on this page (CR 2026-11-11). Red: someone may have to act, possibly with
 * an adjustment. Amber: worth a look. Grey: already handled, shown for the record. A Record, so a
 * new case type cannot be added without choosing its tier. TODO(BR-41): the tiers are a display
 * choice, not a business rule (docs/open-questions.md).
 */
type CaseTier = 'urgent' | 'review' | 'info';
const CASE_TIER: Record<TodayCase, CaseTier> = {
  PROVISIONALLY_CANCELLED: 'urgent',
  LATE_CHECK_IN: 'urgent',
  LATE_DMP_CONFIRMATION: 'urgent',
  ON_LEAVE_WITH_MEALS: 'review',
  EXTRA: 'review',
  ADDED: 'review',
  REDUCED: 'review',
  DISH_CHANGED: 'review',
  NOT_EATING: 'review',
  PRESENCE_CONFIRMED_BY_ADMIN: 'info',
  PRESENCE_CONFIRMED_IN_DMP: 'info',
  CANCELLED_ABSENT: 'info',
};
const TIER_ORDER: Record<CaseTier, number> = { urgent: 0, review: 1, info: 2 };
const TIER_BADGE = { urgent: 'destructive', review: 'warning', info: 'outline' } as const;
const CASE_ORDER = Object.keys(CASE_TIER) as TodayCase[];

type Tab = 'meals' | 'exceptions' | 'adjustments';

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

/** A signed whole number with a real minus sign (U+2212), so "−19" does not read as a dash. */
export function signed(n: number): string {
  if (n > 0) return `+${n}`;
  if (n < 0) return `−${-n}`;
  return '0';
}

const pctFormat = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 1 });

/** A signed percentage in Vietnamese notation: "−10,6", "+3". */
export function signedPct(p: number): string {
  const text = pctFormat.format(Math.abs(p));
  if (p > 0) return `+${text}`;
  if (p < 0) return `−${text}`;
  return text;
}

/** An ISO instant compared with another, both from the server (never the browser's clock). */
const reached = (nowIso: string, atIso: string) => Date.parse(nowIso) >= Date.parse(atIso);

/**
 * /admin/reconciliation (plan 4.1, P4): the day's status and its one main action (finalize,
 * then the final file), the totals against the forecast (BR-40, BR-42), the per-meal comparison
 * by canteen, the cases to review (BR-41) and the adjustments after finalization (BR-46). Dept
 * admins see their units, read-only. It works one day at a time and opens on today; a past day is
 * for looking back and downloading its files again. Cases link to /admin/today, where they are
 * handled (docs/decisions.md).
 */
export function ReconciliationScreen({ can }: { can: ReconciliationPermissions }) {
  const qc = useQueryClient();
  const [date, setDate] = useState('');
  const [orgUnitId, setOrgUnitId] = useState('');
  const [tab, setTab] = useState<Tab>('meals');
  const tabsRef = useRef<HTMLDivElement>(null);

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

  const tabs: Tab[] = ['meals', 'exceptions'];
  if (can.adjust && view?.closed) tabs.push('adjustments');
  const shownTab = tabs.includes(tab) ? tab : 'meals';
  // "Today" is the server's clock, never the browser's.
  const today = view ? vnDateOf(view.now) : undefined;

  const openCases = () => {
    setTab('exceptions');
    tabsRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  };

  return (
    <Card variant="page">
      <CardHeader>
        {/* The day and the unit are what the whole page is about: they sit with the title. */}
        <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-4">
          <div className="flex min-w-0 flex-col gap-1.5">
            <CardTitle>{t('reconciliation.title')}</CardTitle>
            <CardDescription>{t('reconciliation.intro')}</CardDescription>
          </div>
          <div className="flex flex-wrap items-end gap-3">
            <DayNav
              id="recon-date"
              date={date || view?.date || ''}
              today={today}
              onChange={setDate}
            />
            <OrgLevelFilter
              id="recon-org"
              options={orgUnits.data?.items ?? []}
              value={orgUnitId}
              onChange={setOrgUnitId}
            />
          </div>
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-8">
        {query.error ? <Alert variant="destructive">{apiErrorText(query.error)}</Alert> : null}
        {query.isLoading ? <LoadingRows rows={4} /> : null}

        {view ? (
          <Refreshing busy={query.isPlaceholderData} className="flex flex-col gap-8">
            {/* Group 1: where the day stands, and its numbers. */}
            <div className="flex flex-col gap-3">
              {view.servingDay && today && view.date < today ? (
                <Alert>{t('reconciliation.pastDay')}</Alert>
              ) : null}
              <DayStatus view={view} can={can} onChanged={refresh} />
              {view.servingDay ? <Summary view={view} onOpenCases={openCases} /> : null}
            </div>

            {/* Group 2: the detail behind the numbers. */}
            {view.servingDay ? (
              <div ref={tabsRef} className="flex scroll-mt-4 flex-col gap-3">
                <Tabs value={shownTab} onValueChange={(v) => setTab(v as Tab)}>
                  <TabsList>
                    <TabsTrigger value="meals">{t('reconciliation.tabs.meals')}</TabsTrigger>
                    <TabsTrigger value="exceptions">
                      {t('reconciliation.tabs.exceptions', { count: view.exceptions.length })}
                    </TabsTrigger>
                    {tabs.includes('adjustments') ? (
                      <TabsTrigger value="adjustments">
                        {t('reconciliation.tabs.adjustments')}
                      </TabsTrigger>
                    ) : null}
                  </TabsList>
                </Tabs>
                {shownTab === 'meals' ? <MealsTable view={view} /> : null}
                {shownTab === 'exceptions' ? <ExceptionsTable view={view} /> : null}
                {shownTab === 'adjustments' ? (
                  <AdjustmentsTab date={view.date} onChanged={refresh} />
                ) : null}
              </div>
            ) : null}
          </Refreshing>
        ) : null}
      </CardContent>
    </Card>
  );
}

/**
 * One card for where the day stands: the state, the main action (finalize before, the final file
 * after), the day's milestones and the attendance sync line, which decides what finalizing counts.
 */
function DayStatus({
  view,
  can,
  onChanged,
}: {
  view: ReconciliationView;
  can: ReconciliationPermissions;
  onChanged: () => void;
}) {
  const [confirm, confirmDialog] = useConfirm();
  const finalize = useMutation({
    mutationFn: (day: string) =>
      api.post<FinalizeResult>('/admin/reconciliation/finalize', { date: day }),
    onSuccess: (res) => {
      toast.success(
        t('reconciliation.finalized', { date: formatDate(res.date), count: res.meals }),
      );
      onChanged();
    },
  });
  const exportFinal = useMutation({
    mutationFn: (day: string) =>
      api.download(`/admin/reconciliation/export?date=${day}`, `chot-so-${day}.xlsx`),
  });

  const askFinalize = async () => {
    const ok = await confirm({
      title: t('reconciliation.finalizeTitle', { date: formatDate(view.date) }),
      description: [
        t('reconciliation.finalizeDescription', {
          deadline: formatVnTime(view.adjustmentDeadlineAt),
        }),
        view.cutoffHeld
          ? t('reconciliation.finalizeHeld', { cutoff: formatVnTime(view.attendance.cutoffAt) })
          : '',
      ]
        .filter(Boolean)
        .join(' '),
      confirmLabel: t('reconciliation.finalize'),
    });
    if (ok) finalize.mutate(view.date);
  };

  if (!view.servingDay) {
    return (
      <section
        aria-label={t('reconciliation.dayStatus.label')}
        className="flex items-center gap-3 rounded-lg bg-card p-4 text-sm shadow-block"
      >
        <Badge variant="outline">{t('reconciliation.dayStatus.notServing')}</Badge>
        <p className="text-muted-foreground">{t('reconciliation.notServing')}</p>
      </section>
    );
  }

  const closed = view.closed;
  // BR-32: after finalization the re-run is refused from the adjustment deadline on.
  const rerunOpen = !(closed && reached(view.now, view.adjustmentDeadlineAt));

  return (
    <section
      aria-label={t('reconciliation.dayStatus.label')}
      className="flex flex-col rounded-lg bg-card text-sm shadow-block"
    >
      {confirmDialog}
      <div className="flex flex-wrap items-center justify-between gap-4 p-4">
        <div className="flex min-w-0 flex-col gap-1">
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant={closed ? 'success' : 'warning'}>
              {closed ? <Lock aria-hidden /> : null}
              {closed ? t('reconciliation.dayStatus.closed') : t('reconciliation.dayStatus.open')}
            </Badge>
            <p className="font-medium">
              {closed
                ? closed.autoFinalized
                  ? t('reconciliation.closedAuto', { at: formatVnTime(closed.finalizedAt) })
                  : t('reconciliation.closed', {
                      at: formatVnTime(closed.finalizedAt),
                      name: closed.finalizedByName ?? '',
                    })
                : t('reconciliation.finalizeOpensAt', {
                    at: formatVnTime(view.finalizeOpensAt),
                    auto: formatVnTime(view.autoFinalizeAt),
                  })}
            </p>
          </div>
          {closed ? (
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
        </div>
        <div className="flex flex-wrap gap-2">
          {can.finalize && !closed ? (
            <Button
              disabled={!view.canFinalize || finalize.isPending}
              onClick={() => void askFinalize()}
            >
              <Lock className="h-4 w-4" aria-hidden />
              {finalize.isPending ? t('reconciliation.finalizing') : t('reconciliation.finalize')}
            </Button>
          ) : null}
          {/* Once closed, the final file is what goes to the caterer: it is the main action. */}
          {can.export && closed ? (
            <Button disabled={exportFinal.isPending} onClick={() => exportFinal.mutate(view.date)}>
              <Download className="h-4 w-4" aria-hidden />
              {t('reconciliation.exportFinal')}
            </Button>
          ) : null}
        </div>
      </div>

      {finalize.error || exportFinal.error || (!closed && view.cutoffHeld) ? (
        <div className="flex flex-col gap-2 px-4 pb-4">
          {/* Said before the click, not only in the confirm dialog. */}
          {!closed && view.cutoffHeld ? (
            <Alert variant="warning">
              {t('reconciliation.finalizeHeld', {
                cutoff: formatVnTime(view.attendance.cutoffAt),
              })}
            </Alert>
          ) : null}
          {finalize.error ? (
            <Alert variant="destructive">{apiErrorText(finalize.error)}</Alert>
          ) : null}
          {exportFinal.error ? (
            <Alert variant="destructive">{apiErrorText(exportFinal.error)}</Alert>
          ) : null}
        </div>
      ) : null}

      <Milestones view={view} />

      <div className="border-t border-border/60 px-4 py-3">
        <SyncPanel
          compact
          status={view.attendance}
          canSync={can.sync && rerunOpen}
          onDone={onChanged}
        />
      </div>
    </section>
  );
}

/** The day's fixed times, from Setting through the API, ticked off by the server's clock. */
function Milestones({ view }: { view: ReconciliationView }) {
  const items = [
    {
      key: 'cutoff',
      label: t('reconciliation.milestones.cutoff'),
      at: view.attendance.cutoff?.runAt ?? view.attendance.cutoffAt,
      done: view.attendance.cutoff !== null,
      // BR-34: the cutoff ran but cancelled nothing.
      held: !!view.attendance.cutoff && view.attendance.cutoff.outcome !== 'CANCELLED',
    },
    {
      key: 'opens',
      label: t('reconciliation.milestones.opens'),
      at: view.finalizeOpensAt,
      done: reached(view.now, view.finalizeOpensAt),
    },
    {
      key: 'adjust',
      label: t('reconciliation.milestones.adjust'),
      at: view.adjustmentDeadlineAt,
      done: reached(view.now, view.adjustmentDeadlineAt),
    },
    view.closed
      ? {
          key: 'closed',
          label: t('reconciliation.milestones.finalized'),
          at: view.closed.finalizedAt,
          done: true,
        }
      : {
          key: 'auto',
          label: t('reconciliation.milestones.auto'),
          at: view.autoFinalizeAt,
          done: false,
        },
  ];
  return (
    <ol
      aria-label={t('reconciliation.milestones.label')}
      className="flex flex-wrap items-center gap-x-2 gap-y-2 border-t border-border/60 px-4 py-3 text-xs"
    >
      {items.map((m, i) => (
        <li key={m.key} className="flex items-center gap-2">
          {i > 0 ? <span aria-hidden className="h-px w-6 bg-border" /> : null}
          <span
            className={cn(
              'flex h-4 w-4 items-center justify-center rounded-full',
              'held' in m && m.held
                ? 'bg-destructive/10 text-destructive'
                : m.done
                  ? 'bg-success/15 text-success'
                  : 'border border-border',
            )}
            aria-hidden
          >
            {'held' in m && m.held ? (
              <AlertTriangle className="h-3 w-3" />
            ) : m.done ? (
              <Check className="h-3 w-3" />
            ) : null}
          </span>
          <span
            className={cn(
              'font-medium',
              'held' in m && m.held
                ? 'text-destructive'
                : m.done
                  ? 'text-muted-foreground'
                  : 'text-foreground',
            )}
          >
            <span className="font-medium tabular-nums">{formatVnTime(m.at)}</span> {m.label}
          </span>
        </li>
      ))}
    </ol>
  );
}

/**
 * The day's totals. Before finalization: forecast, actual (with its difference) and the cases.
 * After: forecast, final (what the caterer got), adjustments, effective (what counts) and the
 * cases. "Actual" is not shown next to "final": once closed they are the same number.
 */
function Summary({ view, onOpenCases }: { view: ReconciliationView; onOpenCases: () => void }) {
  const s = view.summary;
  const diffText =
    s.forecast > 0
      ? t('reconciliation.summary.differencePct', {
          diff: signed(s.difference),
          pct: signedPct((s.difference / s.forecast) * 100),
        })
      : t('reconciliation.summary.difference', { diff: signed(s.difference) });
  const cases = (
    <button
      type="button"
      onClick={onOpenCases}
      aria-label={t('reconciliation.summary.openCases', { count: s.cases })}
      className="group col-span-2 flex flex-col gap-1 rounded-lg bg-card p-4 text-left shadow-block sm:col-span-1 transition-colors hover:bg-muted/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
    >
      <span className="flex items-center justify-between text-sm text-muted-foreground">
        {t('reconciliation.summary.cases')}
        <ArrowRight
          className="h-4 w-4 opacity-60 transition-transform group-hover:translate-x-0.5"
          aria-hidden
        />
      </span>
      <span className="text-2xl font-semibold tabular-nums">
        {s.cases}{' '}
        <span className="text-sm font-normal text-muted-foreground">
          {t('reconciliation.summary.casesPeople')}
        </span>
      </span>
      <span
        className={cn(
          'text-xs font-medium',
          s.deviatingMeals > 0 ? 'text-foreground' : 'text-muted-foreground',
        )}
      >
        {s.deviatingMeals > 0
          ? t('reconciliation.summary.deviatingMeals', { count: s.deviatingMeals })
          : t('reconciliation.summary.noDeviatingMeals')}
      </span>
    </button>
  );

  if (!view.closed) {
    return (
      <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        <Stat label={t('reconciliation.summary.forecast')} value={s.forecast} />
        <Stat label={t('reconciliation.summary.actual')} value={s.actual} hint={diffText} />
        {cases}
      </dl>
    );
  }
  return (
    <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
      <Stat label={t('reconciliation.summary.forecast')} value={s.forecast} />
      <Stat label={t('reconciliation.summary.final')} value={s.finalCount ?? 0} hint={diffText} />
      <Stat label={t('reconciliation.summary.adjustment')} value={signed(s.adjustment ?? 0)} />
      <Stat
        label={t('reconciliation.summary.effective')}
        value={s.effective ?? 0}
        hint={t('reconciliation.summary.effectiveHint')}
        emphasis
      />
      {cases}
    </dl>
  );
}

function Stat({
  label,
  value,
  hint,
  emphasis = false,
}: {
  label: string;
  value: number | string;
  hint?: string;
  emphasis?: boolean;
}) {
  return (
    <div
      className={cn(
        'flex flex-col gap-1 rounded-lg p-4 shadow-block',
        emphasis ? 'bg-primary/5 ring-1 ring-primary/20' : 'bg-card',
      )}
    >
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className={cn('text-2xl font-semibold tabular-nums', emphasis && 'text-primary')}>
        {value}
      </dd>
      {hint ? <dd className="text-xs font-medium text-muted-foreground">{hint}</dd> : null}
    </div>
  );
}

interface CanteenGroup {
  canteenId: string;
  canteenName: string;
  meals: ReconciliationMeal[];
}

/** Meals grouped by canteen, in the API's order: admins read the counts per canteen. */
function byCanteen(meals: readonly ReconciliationMeal[]): CanteenGroup[] {
  const groups = new Map<string, CanteenGroup>();
  for (const m of meals) {
    const g = groups.get(m.canteenId) ?? {
      canteenId: m.canteenId,
      canteenName: m.canteenName,
      meals: [],
    };
    g.meals.push(m);
    groups.set(m.canteenId, g);
  }
  return [...groups.values()];
}

interface Totals {
  forecast: number;
  actual: number;
  difference: number;
  adjustment: number;
  effective: number;
}

function totalsOf(meals: readonly ReconciliationMeal[]): Totals {
  return meals.reduce<Totals>(
    (s, m) => ({
      forecast: s.forecast + m.forecast,
      actual: s.actual + m.actual,
      difference: s.difference + m.difference,
      adjustment: s.adjustment + (m.adjustment ?? 0),
      effective: s.effective + (m.effective ?? 0),
    }),
    { forecast: 0, actual: 0, difference: 0, adjustment: 0, effective: 0 },
  );
}

/** Zero changes are a muted dash, so the non-zero ones stand out. */
function Delta({ n }: { n: number }) {
  return n === 0 ? (
    <span className="text-muted-foreground">{t('common.none')}</span>
  ) : (
    <>{signed(n)}</>
  );
}

function MealStatus({ meal }: { meal: ReconciliationMeal }) {
  if (meal.deviates) {
    return (
      <Badge variant="warning">
        {meal.deviationPct === null
          ? t('reconciliation.deviationNoForecast')
          : t('reconciliation.deviation', { pct: signedPct(meal.deviationPct) })}
      </Badge>
    );
  }
  return (
    <Badge variant={meal.status === 'MATCHED' ? 'success' : 'outline'}>
      {t(`reconciliation.status.${meal.status as 'MATCHED' | 'MISMATCHED'}`)}
    </Badge>
  );
}

/**
 * BR-40 / BR-42: forecast, actual (final once closed), difference and status per meal, grouped by
 * canteen with a subtotal per canteen and the day's total. One status badge per meal; the
 * threshold is said once, above the table.
 */
function MealsTable({ view }: { view: ReconciliationView }) {
  if (view.meals.length === 0) {
    return <p className="text-sm text-muted-foreground">{t('reconciliation.noMeals')}</p>;
  }
  const closed = view.closed !== null;
  const groups = byCanteen(view.meals);
  const num = 'w-28 text-right tabular-nums';
  const numbers = (x: Totals) => (
    <>
      <TableCell className={num}>{x.forecast}</TableCell>
      <TableCell className={num}>{x.actual}</TableCell>
      <TableCell className={num}>
        <Delta n={x.difference} />
      </TableCell>
      {closed ? (
        <>
          <TableCell className={num}>
            <Delta n={x.adjustment} />
          </TableCell>
          <TableCell className={cn(num, 'font-semibold')}>{x.effective}</TableCell>
        </>
      ) : null}
    </>
  );
  const total = totalsOf(view.meals);
  return (
    <div className="flex flex-col gap-2">
      <p className="text-xs text-muted-foreground">
        {t('reconciliation.threshold', { threshold: view.deviationThresholdPct })}
      </p>
      <Table className="min-w-[44rem]">
        <TableHeader>
          <TableRow>
            <TableHead>{t('reconciliation.columns.meal')}</TableHead>
            <TableHead className="text-right">{t('reconciliation.columns.forecast')}</TableHead>
            <TableHead className="text-right">
              {closed ? t('reconciliation.columns.final') : t('reconciliation.columns.actual')}
            </TableHead>
            <TableHead className="text-right">{t('reconciliation.columns.difference')}</TableHead>
            {closed ? (
              <>
                <TableHead className="text-right">
                  {t('reconciliation.columns.adjustment')}
                </TableHead>
                <TableHead className="text-right">
                  {t('reconciliation.columns.effective')}
                </TableHead>
              </>
            ) : null}
            <TableHead className="w-44">{t('reconciliation.columns.status')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody striped={false}>
          {groups.map((g) => (
            <Fragment key={g.canteenId}>
              <TableRow className="bg-muted/40 font-semibold hover:bg-muted/40">
                <TableCell>{g.canteenName}</TableCell>
                {numbers(totalsOf(g.meals))}
                <TableCell />
              </TableRow>
              {g.meals.map((m) => (
                <TableRow key={m.menuItemId}>
                  <TableCell className="pl-8">{m.displayName}</TableCell>
                  {numbers({
                    forecast: m.forecast,
                    actual: m.actual,
                    difference: m.difference,
                    adjustment: m.adjustment ?? 0,
                    effective: m.effective ?? 0,
                  })}
                  <TableCell>
                    <MealStatus meal={m} />
                  </TableCell>
                </TableRow>
              ))}
            </Fragment>
          ))}
        </TableBody>
        {groups.length > 1 ? (
          <tfoot className="border-t-2 border-border">
            <TableRow className="font-semibold hover:bg-transparent">
              <TableCell>{t('reconciliation.total')}</TableCell>
              {numbers(total)}
              <TableCell />
            </TableRow>
          </tfoot>
        ) : null}
      </Table>
    </div>
  );
}

/** The person's row on /admin/today for that day: the search box is filled with their code. */
export function todayHref(date: string, employeeCode: string): string {
  return `/admin/today?${new URLSearchParams({ date, q: employeeCode })}`;
}

const rowTier = (r: TodayRow) => Math.min(...r.cases.map((c) => TIER_ORDER[CASE_TIER[c]]), 3);

/** "1 × Cơm gà, 2 × Phở", or `empty` when there is none. */
function mealsText(lines: { displayName: string; quantity: number }[], empty: string): string {
  return (
    lines
      .filter((l) => l.quantity > 0)
      .map((l) => `${l.quantity} × ${l.displayName}`)
      .join(', ') || empty
  );
}

/** Forecast → actual in one cell; a single text when nothing changed. */
function MealsChange({ row }: { row: TodayRow }) {
  const before = mealsText(
    row.lines.map((l) => ({ displayName: l.displayName, quantity: l.forecast })),
    t('reconciliation.noForecastMeal'),
  );
  const after = row.mealsCount
    ? mealsText(row.lines, t('reconciliation.noMeal'))
    : t('reconciliation.notCounted');
  if (before === after) return <span>{after}</span>;
  return (
    <span className="flex flex-wrap items-center gap-x-1.5">
      <span className="text-muted-foreground">{before}</span>
      <ArrowRight className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-label="→" />
      <span className="font-medium">{after}</span>
    </span>
  );
}

/**
 * BR-41: who, their unit (full path, BR-61), forecast → actual meals and the cases, the most
 * urgent first, filterable by case. Each row links to /admin/today, where it is handled.
 */
function ExceptionsTable({ view }: { view: ReconciliationView }) {
  const [filter, setFilter] = useState<TodayCase | null>(null);
  if (view.exceptions.length === 0) {
    return <p className="text-sm text-muted-foreground">{t('reconciliation.noExceptions')}</p>;
  }
  const counts = new Map<TodayCase, number>();
  for (const r of view.exceptions) {
    for (const c of r.cases) counts.set(c, (counts.get(c) ?? 0) + 1);
  }
  const present = [...counts.keys()].sort(
    (a, b) =>
      TIER_ORDER[CASE_TIER[a]] - TIER_ORDER[CASE_TIER[b]] ||
      CASE_ORDER.indexOf(a) - CASE_ORDER.indexOf(b),
  );
  const shownFilter = filter && counts.has(filter) ? filter : null;
  const rows = view.exceptions
    .filter((r) => !shownFilter || r.cases.includes(shownFilter))
    .map((r, i) => ({ r, i }))
    .sort((a, b) => rowTier(a.r) - rowTier(b.r) || a.i - b.i)
    .map(({ r }) => r);

  const chip = (active: boolean) =>
    cn(
      'rounded-full px-3 py-1 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
      active
        ? 'bg-foreground text-background'
        : 'bg-muted text-muted-foreground hover:bg-accent hover:text-foreground',
    );

  return (
    <div className="flex flex-col gap-3">
      <p className="text-xs text-muted-foreground">{t('reconciliation.exceptionsHint')}</p>
      <div
        role="group"
        aria-label={t('reconciliation.filterLabel')}
        className="flex flex-wrap gap-2"
      >
        <button
          type="button"
          aria-pressed={shownFilter === null}
          className={chip(shownFilter === null)}
          onClick={() => setFilter(null)}
        >
          {t('reconciliation.filterAll', { count: view.exceptions.length })}
        </button>
        {present.map((c) => (
          <button
            key={c}
            type="button"
            aria-pressed={shownFilter === c}
            className={chip(shownFilter === c)}
            onClick={() => setFilter(shownFilter === c ? null : c)}
          >
            {t('reconciliation.filterCase', {
              label: t(`adminToday.cases.${c}`),
              count: counts.get(c) ?? 0,
            })}
          </button>
        ))}
      </div>
      <Table className="min-w-[48rem]">
        <TableHeader>
          <TableRow>
            <TableHead>{t('reconciliation.columns.employee')}</TableHead>
            <TableHead>{t('reconciliation.columns.meals')}</TableHead>
            <TableHead>{t('reconciliation.columns.cases')}</TableHead>
            <TableHead className="w-12">
              <span className="sr-only">{t('common.actions')}</span>
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((r) => (
            <TableRow key={r.employeeCode}>
              <TableCell className="min-w-56">
                <span className="block font-medium">{r.name}</span>
                <span className="block text-xs text-muted-foreground">
                  <span className="font-mono">{r.employeeCode}</span> · {r.orgPath}
                </span>
              </TableCell>
              <TableCell className="text-sm">
                <MealsChange row={r} />
              </TableCell>
              <TableCell>
                <div className="flex flex-wrap gap-1">
                  {r.cases.map((c) => (
                    <Badge key={c} variant={TIER_BADGE[CASE_TIER[c]]}>
                      {t(`adminToday.cases.${c}`)}
                    </Badge>
                  ))}
                </div>
              </TableCell>
              <TableCell className="text-right">
                {/* Cases are handled per person on /admin/today: this page only counts them. */}
                <Tooltip>
                  <TooltipTrigger asChild>
                    <Button asChild variant="ghost" size="icon">
                      <Link
                        href={todayHref(view.date, r.employeeCode)}
                        aria-label={t('reconciliation.openInTodayFor', { name: r.name })}
                      >
                        <ExternalLink className="h-4 w-4" aria-hidden />
                      </Link>
                    </Button>
                  </TooltipTrigger>
                  <TooltipContent>{t('reconciliation.openInToday')}</TooltipContent>
                </Tooltip>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

/** BR-46: the day's adjustments as a table, their files, and the form to add one. */
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
    <div className="flex flex-col gap-3">
      {query.error ? <Alert variant="destructive">{apiErrorText(query.error)}</Alert> : null}
      {download.error ? <Alert variant="destructive">{apiErrorText(download.error)}</Alert> : null}
      {view ? (
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-sm text-muted-foreground">
            {view.canAdjust
              ? t('reconciliation.adjustUntil', {
                  deadline: formatVnTime(view.adjustmentDeadlineAt),
                })
              : t('reconciliation.adjustClosed', {
                  deadline: formatVnTime(view.adjustmentDeadlineAt),
                })}
          </p>
          {view.canAdjust ? (
            <Button onClick={() => setOpen(true)}>
              <Plus className="h-4 w-4" aria-hidden />
              {t('reconciliation.adjustments.add')}
            </Button>
          ) : null}
        </div>
      ) : null}
      {view && view.items.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t('reconciliation.adjustments.empty')}</p>
      ) : null}
      {view && view.items.length > 0 ? (
        <Table className="min-w-[52rem]">
          <TableHeader>
            <TableRow>
              <TableHead className="w-14">
                {t('reconciliation.adjustments.columns.number')}
              </TableHead>
              <TableHead>{t('reconciliation.adjustments.columns.createdAt')}</TableHead>
              <TableHead>{t('reconciliation.adjustments.columns.lines')}</TableHead>
              <TableHead>{t('reconciliation.adjustments.columns.reason')}</TableHead>
              <TableHead className="text-right">
                {t('reconciliation.adjustments.columns.total')}
              </TableHead>
              <TableHead>{t('reconciliation.adjustments.columns.caterer')}</TableHead>
              <TableHead className="w-12">
                <span className="sr-only">{t('common.actions')}</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {view.items.map((a) => (
              <TableRow key={a.id}>
                <TableCell className="tabular-nums">{a.number}</TableCell>
                <TableCell>
                  <span className="block tabular-nums">{formatVnTime(a.createdAt)}</span>
                  <span className="block text-xs text-muted-foreground">{a.createdByName}</span>
                </TableCell>
                <TableCell>
                  <ul className="flex flex-col gap-0.5">
                    {a.lines.map((l) => (
                      <li key={`${l.employeeCode}-${l.menuItemId}`}>
                        {l.employeeName}{' '}
                        <span className="font-mono text-xs text-muted-foreground">
                          {l.employeeCode}
                        </span>
                        : <span className="tabular-nums">{signed(l.delta)}</span> × {l.displayName}
                      </li>
                    ))}
                  </ul>
                </TableCell>
                <TableCell className="max-w-64">{a.reason}</TableCell>
                <TableCell className="text-right font-semibold tabular-nums">
                  {signed(a.totalDelta)}
                </TableCell>
                <TableCell>
                  {a.catererConfirmed ? (
                    <Badge variant="success">{t('reconciliation.adjustments.catererYes')}</Badge>
                  ) : (
                    <span className="text-muted-foreground">{t('common.none')}</span>
                  )}
                </TableCell>
                <TableCell className="text-right">
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Button
                        size="icon"
                        variant="ghost"
                        disabled={download.isPending}
                        aria-label={t('reconciliation.adjustments.fileFor', { number: a.number })}
                        onClick={() => download.mutate(a)}
                      >
                        <Download className="h-4 w-4" aria-hidden />
                      </Button>
                    </TooltipTrigger>
                    <TooltipContent>{t('reconciliation.adjustments.file')}</TooltipContent>
                  </Tooltip>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      ) : null}
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
 * BR-46: one employee's adjustment. The admin sets each meal's new count with a stepper (from 0,
 * so never below zero); the change sent is new − current. A reason is required and, only when a
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
  const [counts, setCounts] = useState<Record<string, number>>({});
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
  const meals = day.data?.meals ?? [];
  const lines = meals
    .map((m) => ({
      menuItemId: m.menuItemId,
      delta: (counts[m.menuItemId] ?? m.effective) - m.effective,
    }))
    .filter((l) => l.delta !== 0);
  const adds = lines.some((l) => l.delta > 0);
  const save = useMutation({
    mutationFn: () =>
      api.post<AdjustmentCreated>('/admin/adjustments', {
        date,
        reason: reason.trim(),
        catererConfirmed: adds && caterer,
        lines: lines.map((l) => ({ ...l, employeeCode: employee!.employeeCode })),
      }),
    onSuccess: (res) => {
      reset();
      onDone(res);
    },
  });
  function reset() {
    setEmployee(null);
    setCounts({});
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
              setCounts({});
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
            {meals.map((m) => {
              const next = counts[m.menuItemId] ?? m.effective;
              return (
                <div key={m.menuItemId} className="flex items-center justify-between gap-3">
                  <div className="flex flex-col text-sm">
                    <span>{m.displayName}</span>
                    <span
                      className={cn(
                        'text-xs',
                        next === m.effective ? 'text-muted-foreground' : 'font-medium text-primary',
                      )}
                    >
                      {next === m.effective
                        ? t('reconciliation.adjustments.current', { count: m.effective })
                        : t('reconciliation.adjustments.change', {
                            from: m.effective,
                            to: next,
                            delta: signed(next - m.effective),
                          })}
                    </span>
                  </div>
                  <QuantityStepper
                    value={next}
                    onChange={(n) => setCounts((prev) => ({ ...prev, [m.menuItemId]: n }))}
                    label={t('reconciliation.adjustments.quantity', { dish: m.displayName })}
                    increaseLabel={t('week.increase', { dish: m.displayName })}
                    decreaseLabel={t('week.decrease', { dish: m.displayName })}
                  />
                </div>
              );
            })}
          </fieldset>
        ) : null}
        <div className="flex flex-col gap-2">
          <Label htmlFor="adjustment-reason">{t('reconciliation.adjustments.reason')}</Label>
          <textarea
            id="adjustment-reason"
            maxLength={500}
            rows={3}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            className={cn(fieldSurface, 'min-h-20 w-full resize-y px-3 py-2 text-base md:text-sm')}
          />
        </div>
        {/* BR-46: asked only when a meal is added. */}
        {adds ? (
          <div className="flex items-start gap-2">
            <Checkbox
              id="adjustment-caterer"
              checked={caterer}
              onCheckedChange={(c) => setCaterer(c === true)}
            />
            <Label htmlFor="adjustment-caterer" className="flex flex-col">
              <span>{t('reconciliation.adjustments.caterer')}</span>
              <span className="text-xs text-muted-foreground">
                {t('reconciliation.adjustments.catererRequired')}
              </span>
            </Label>
          </div>
        ) : null}
      </form>
    </AppDialog>
  );
}
