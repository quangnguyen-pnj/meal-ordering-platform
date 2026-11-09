'use client';

import type {
  AdminTodayView,
  AttendanceRerunResult,
  AttendanceStatus,
  OrgFilterOption,
  TodayCase,
  TodayPresence,
  TodayRow,
} from '@/lib/api-types';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { toast } from 'sonner';
import { AppDialog } from '@/components/app-dialog';
import { OrgLevelFilter } from '@/components/org-level-filter';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { DatePicker } from '@/components/ui/date-picker';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Pagination } from '@/components/ui/pagination';
import { QuantityStepper } from '@/components/ui/quantity-stepper';
import { LoadingRows } from '@/components/ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useDebounced } from '@/hooks/use-debounced';
import { usePagination } from '@/hooks/use-pagination';
import { api } from '@/lib/api';
import { apiErrorText } from '@/lib/errors';
import { linesOf } from '@/lib/registration-draft';
import { BANNERS_KEY } from '@/lib/banners';
import { formatDate, formatVnTime } from '@/lib/utils';
import { t } from '@/messages';
import { weekdayName } from './day-meals';

const KEY = '/admin/today';

const PRESENCE_VARIANT = {
  UNKNOWN: 'outline',
  PRESENT: 'success',
  PROVISIONAL_ABSENT: 'destructive',
  ABSENT: 'destructive',
} as const;

/** Cases that need the admin's attention first (BR-41); the others are informative. */
const URGENT: readonly TodayCase[] = ['PROVISIONALLY_CANCELLED', 'CANCELLED_ABSENT'];

function matches(row: TodayRow, term: string): boolean {
  return (
    !term ||
    row.employeeCode.toLocaleLowerCase('vi').includes(term) ||
    row.name.toLocaleLowerCase('vi').includes(term)
  );
}

/**
 * /admin/today (plan 4.1, P3): day D of the caller's units. Meals and forecast per employee,
 * attendance (BR-30 to BR-32), leave flags (BR-35) and the cases to review (BR-41). Admins confirm
 * presence with an optional reason (BR-33) and, after the lock, edit meals with a reason (BR-24).
 * The HC admin re-runs the attendance sync (BR-34).
 */
export function AdminTodayScreen({ canSync }: { canSync: boolean }) {
  const qc = useQueryClient();
  const [date, setDate] = useState('');
  const [orgUnitId, setOrgUnitId] = useState('');
  const [tab, setTab] = useState<'all' | 'exceptions'>('all');
  const [q, setQ] = useState('');
  const term = useDebounced(q.trim().toLocaleLowerCase('vi'), 200);
  const [confirmFor, setConfirmFor] = useState<TodayRow | null>(null);
  const [editFor, setEditFor] = useState<TodayRow | null>(null);

  const query = useQuery({
    queryKey: [KEY, date, orgUnitId],
    queryFn: () => {
      const params = new URLSearchParams();
      if (date) params.set('date', date);
      if (orgUnitId) params.set('orgUnitId', orgUnitId);
      return api.get<AdminTodayView>(`/admin/today${params.size ? `?${params}` : ''}`);
    },
  });
  const orgUnits = useQuery({
    queryKey: ['/admin/registrations/org-units'],
    queryFn: () => api.get<{ items: OrgFilterOption[] }>('/admin/registrations/org-units'),
  });
  const view = query.data;
  const rows = view?.rows ?? [];
  const exceptions = rows.filter((r) => r.cases.length > 0);
  const shown = (tab === 'exceptions' ? exceptions : rows).filter((r) => matches(r, term));
  // Provisionally cancelled people first on the exceptions tab: they lose their meal otherwise.
  if (tab === 'exceptions') {
    shown.sort(
      (a, b) =>
        Number(b.cases.some((c) => URGENT.includes(c))) -
        Number(a.cases.some((c) => URGENT.includes(c))),
    );
  }
  const { pageItems, pagination } = usePagination(
    shown,
    `${view?.date}|${orgUnitId}|${tab}|${term}`,
  );
  const refresh = () => qc.invalidateQueries({ queryKey: [KEY] });

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('adminToday.title')}</CardTitle>
        <CardDescription>
          {view ? `${weekdayName(view.date)} ${formatDate(view.date)} · ` : ''}
          {t('adminToday.intro', { lock: view ? formatVnTime(view.lockAt) : '08:00' })}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        <div className="flex flex-wrap items-end gap-4">
          <div className="flex w-full flex-col gap-2 sm:w-44">
            <Label htmlFor="today-date">{t('adminToday.date')}</Label>
            <DatePicker
              id="today-date"
              value={date || view?.date || ''}
              onChange={setDate}
              placeholder={t('adminToday.datePlaceholder')}
            />
          </div>
          <div className="flex w-full flex-col gap-2 sm:w-56">
            <Label htmlFor="today-q">{t('common.search')}</Label>
            <Input id="today-q" type="search" value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          <OrgLevelFilter
            id="today-org"
            options={orgUnits.data?.items ?? []}
            value={orgUnitId}
            onChange={setOrgUnitId}
          />
        </div>

        {query.error ? <Alert variant="destructive">{apiErrorText(query.error)}</Alert> : null}
        {query.isLoading ? <LoadingRows rows={4} /> : null}

        {view ? (
          <>
            <SyncPanel status={view.attendance} canSync={canSync} onDone={refresh} />
            <dl className="grid grid-cols-2 gap-3 sm:grid-cols-5">
              <Stat label={t('adminToday.summary.withMeals')} value={view.summary.withMeals} />
              <Stat label={t('adminToday.summary.present')} value={view.summary.present} />
              <Stat
                label={t('adminToday.summary.provisional')}
                value={view.summary.provisionallyCancelled}
                tone={view.summary.provisionallyCancelled > 0 ? 'destructive' : undefined}
              />
              <Stat label={t('adminToday.summary.counted')} value={view.summary.countedMeals} />
              <Stat label={t('adminToday.summary.forecast')} value={view.summary.forecastMeals} />
            </dl>

            <Tabs value={tab} onValueChange={(v) => setTab(v as 'all' | 'exceptions')}>
              <TabsList>
                <TabsTrigger value="all">{t('adminToday.tabs.all')}</TabsTrigger>
                <TabsTrigger value="exceptions">
                  {t('adminToday.tabs.exceptions', { count: exceptions.length })}
                </TabsTrigger>
              </TabsList>
            </Tabs>

            {rows.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t('adminToday.empty')}</p>
            ) : (
              <>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t('common.name')}</TableHead>
                      <TableHead>{t('adminToday.columns.meals')}</TableHead>
                      <TableHead>{t('adminToday.columns.presence')}</TableHead>
                      <TableHead className="hidden lg:table-cell">
                        {t('adminToday.columns.cases')}
                      </TableHead>
                      <TableHead className="w-0">
                        <span className="sr-only">{t('common.actions')}</span>
                      </TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {pageItems.map((row) => (
                      <TodayTableRow
                        key={row.employeeCode}
                        row={row}
                        view={view}
                        onConfirm={() => setConfirmFor(row)}
                        onEdit={() => setEditFor(row)}
                      />
                    ))}
                    {shown.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={5} className="text-muted-foreground">
                          {tab === 'exceptions' && !term
                            ? t('adminToday.noExceptions')
                            : t('adminToday.noMatch')}
                        </TableCell>
                      </TableRow>
                    ) : null}
                  </TableBody>
                </Table>
                <Pagination {...pagination} />
              </>
            )}

            <ConfirmPresenceDialog
              row={confirmFor}
              date={view.date}
              onClose={() => setConfirmFor(null)}
              onDone={(name) => {
                setConfirmFor(null);
                toast.success(t('adminToday.confirmed', { name }));
                refresh();
              }}
            />
            <EditMealsDialog
              row={editFor}
              view={view}
              onClose={() => setEditFor(null)}
              onDone={(name) => {
                setEditFor(null);
                toast.success(t('adminToday.edited', { name }));
                refresh();
              }}
            />
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}

function Stat({ label, value, tone }: { label: string; value: number; tone?: 'destructive' }) {
  return (
    <div className="rounded-lg bg-card p-4 shadow-block">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd
        className={`text-xl font-semibold tabular-nums ${tone === 'destructive' ? 'text-destructive' : ''}`}
      >
        {value}
      </dd>
    </div>
  );
}

function SyncPanel({
  status,
  canSync,
  onDone,
}: {
  status: AttendanceStatus;
  canSync: boolean;
  onDone: () => void;
}) {
  const qc = useQueryClient();
  const rerun = useMutation({
    mutationFn: () =>
      api.post<AttendanceRerunResult>('/admin/attendance/sync', { date: status.date }),
    onSuccess: () => {
      toast.success(t('adminToday.sync.rerunDone'));
      onDone();
      void qc.invalidateQueries({ queryKey: BANNERS_KEY });
    },
  });
  if (!status.servingDay) {
    return <p className="text-sm text-muted-foreground">{t('adminToday.sync.notServing')}</p>;
  }
  const sync = status.lastAttendanceSync;
  const cutoff = status.cutoff;
  return (
    <section
      aria-label={t('adminToday.sync.title')}
      className="flex flex-col gap-2 rounded-lg bg-card p-4 text-sm shadow-block"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-medium">{t('adminToday.sync.title')}</h2>
        {canSync ? (
          <Button
            size="sm"
            variant="outline"
            disabled={rerun.isPending}
            onClick={() => rerun.mutate()}
          >
            {rerun.isPending ? t('adminToday.sync.rerunning') : t('adminToday.sync.rerun')}
          </Button>
        ) : null}
      </div>
      {rerun.error ? <Alert variant="destructive">{apiErrorText(rerun.error)}</Alert> : null}
      <p className={sync?.status === 'FAILED' ? 'text-destructive' : ''}>
        {sync
          ? t('adminToday.sync.last', {
              at: formatVnTime(sync.startedAt),
              status: t(`adminToday.sync.status.${sync.status}`),
            })
          : t('adminToday.sync.never')}
      </p>
      {sync?.error ? (
        <p className="text-destructive">{t('adminToday.sync.error', { error: sync.error })}</p>
      ) : null}
      <p className={cutoff && cutoff.outcome !== 'CANCELLED' ? 'text-destructive' : ''}>
        {cutoff
          ? t(`adminToday.sync.cutoff.${cutoff.outcome}`, {
              at: formatVnTime(cutoff.runAt),
              count: cutoff.cancelledCount,
              present: cutoff.presentWithMeals,
              total: cutoff.withMeals,
            })
          : t('adminToday.sync.cutoffPending', { cutoff: formatVnTime(status.cutoffAt) })}
      </p>
      {status.lastLeaveSync?.finishedAt ? (
        <p className="text-muted-foreground">
          {t('adminToday.sync.leave', { at: formatVnTime(status.lastLeaveSync.finishedAt) })}
        </p>
      ) : null}
    </section>
  );
}

function PresenceCell({ presence }: { presence: TodayPresence }) {
  return (
    <div className="flex flex-col items-start gap-1">
      <Badge variant={PRESENCE_VARIANT[presence.state]}>
        {t(`adminToday.presence.${presence.state}`)}
      </Badge>
      {presence.firstCheckInAt ? (
        <span className="text-xs text-muted-foreground">
          {t('adminToday.checkIn', { time: formatVnTime(presence.firstCheckInAt) })}
        </span>
      ) : null}
      {presence.confirmedInDmp ? (
        <span className="text-xs text-muted-foreground">{t('adminToday.confirmedInDmp')}</span>
      ) : null}
      {presence.confirmedAt ? (
        <span className="text-xs text-muted-foreground">
          {t('adminToday.confirmedBy', {
            name: presence.confirmedByName ?? '',
            time: formatVnTime(presence.confirmedAt),
          })}
          {presence.confirmReason ? ` · ${presence.confirmReason}` : ''}
        </span>
      ) : null}
    </div>
  );
}

function TodayTableRow({
  row,
  view,
  onConfirm,
  onEdit,
}: {
  row: TodayRow;
  view: AdminTodayView;
  onConfirm: () => void;
  onEdit: () => void;
}) {
  const cases = row.cases.map((c) => (
    <Badge key={c} variant={URGENT.includes(c) ? 'destructive' : 'warning'}>
      {t(`adminToday.cases.${c}`)}
    </Badge>
  ));
  const canConfirm = view.canConfirmPresence && row.presence.state !== 'PRESENT';
  const canEdit = view.window === 'EXTRA' && row.canteenId !== null;
  return (
    <TableRow>
      <TableCell>
        <p className="font-medium">{row.name}</p>
        <p className="text-xs text-muted-foreground">
          <span className="tabular-nums">{row.employeeCode}</span> · {row.orgPath}
        </p>
        {row.leave ? (
          <Badge variant="outline" className="mt-1">
            {t(`adminToday.leave.${row.leave.dayType}`)} · {row.leave.leaveTypeName}
          </Badge>
        ) : null}
        {/* Narrow screens: the cases fold under the name. */}
        {cases.length > 0 ? (
          <div className="mt-1 flex flex-wrap gap-1 lg:hidden">{cases}</div>
        ) : null}
      </TableCell>
      <TableCell>
        {row.actualTotal === 0 ? (
          <span className="text-muted-foreground">{t('adminToday.noMeal')}</span>
        ) : null}
        <ul className={row.mealsCount ? '' : 'text-muted-foreground line-through'}>
          {row.lines
            .filter((l) => l.quantity > 0)
            .map((l) => (
              <li key={l.menuItemId}>
                {l.quantity} × {l.displayName}
              </li>
            ))}
        </ul>
        {row.forecastTotal !== null && row.forecastTotal !== row.actualTotal ? (
          <span className="text-xs text-muted-foreground">
            {t('adminToday.forecastShort', { count: row.forecastTotal })}
          </span>
        ) : null}
      </TableCell>
      <TableCell>
        <PresenceCell presence={row.presence} />
      </TableCell>
      <TableCell className="hidden lg:table-cell">
        <div className="flex flex-wrap gap-1">{cases}</div>
      </TableCell>
      <TableCell>
        <div className="flex flex-col gap-1 sm:flex-row">
          {canConfirm ? (
            <Button size="sm" variant="outline" onClick={onConfirm}>
              {t('adminToday.confirm')}
            </Button>
          ) : null}
          {canEdit ? (
            <Button size="sm" variant="ghost" onClick={onEdit}>
              {t('adminToday.edit')}
            </Button>
          ) : null}
        </div>
      </TableCell>
    </TableRow>
  );
}

function ConfirmPresenceDialog({
  row,
  date,
  onClose,
  onDone,
}: {
  row: TodayRow | null;
  date: string;
  onClose: () => void;
  onDone: (name: string) => void;
}) {
  const [reason, setReason] = useState('');
  const confirm = useMutation({
    mutationFn: (r: TodayRow) =>
      api.post(`/admin/today/employees/${encodeURIComponent(r.employeeCode)}/presence`, {
        date,
        reason: reason.trim() || null,
      }),
    onSuccess: (_, r) => {
      setReason('');
      onDone(r.name);
    },
  });
  const close = () => {
    confirm.reset();
    setReason('');
    onClose();
  };
  return (
    <AppDialog
      open={row !== null}
      onClose={close}
      title={t('adminToday.confirmTitle', { name: row?.name ?? '' })}
      description={t('adminToday.confirmDescription')}
      footer={
        <>
          <Button variant="outline" onClick={close}>
            {t('common.cancel')}
          </Button>
          <Button form="presence-form" type="submit" disabled={confirm.isPending}>
            {confirm.isPending ? t('common.saving') : t('adminToday.confirm')}
          </Button>
        </>
      }
    >
      <form
        id="presence-form"
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (row) confirm.mutate(row);
        }}
      >
        {confirm.error ? <Alert variant="destructive">{apiErrorText(confirm.error)}</Alert> : null}
        <div className="flex flex-col gap-2">
          <Label htmlFor="presence-reason">{t('adminToday.reasonOptional')}</Label>
          <Input
            id="presence-reason"
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </div>
      </form>
    </AppDialog>
  );
}

function EditMealsDialog({
  row,
  view,
  onClose,
  onDone,
}: {
  row: TodayRow | null;
  view: AdminTodayView;
  onClose: () => void;
  onDone: (name: string) => void;
}) {
  const menu = view.menus.find((m) => m.canteenId === row?.canteenId);
  const [quantities, setQuantities] = useState<Record<string, number>>({});
  const [reason, setReason] = useState('');
  const [openedFor, setOpenedFor] = useState<string | null>(null);
  // Starts from the row's current meals each time the dialog opens for someone.
  if (row && openedFor !== row.employeeCode) {
    setOpenedFor(row.employeeCode);
    setQuantities(Object.fromEntries(row.lines.map((l) => [l.menuItemId, l.quantity])));
    setReason('');
  }
  if (!row && openedFor !== null) setOpenedFor(null);
  const save = useMutation({
    mutationFn: (r: TodayRow) => {
      const onMenu = new Set(menu?.items.map((i) => i.id));
      const lines = linesOf(
        Object.fromEntries(Object.entries(quantities).filter(([id]) => onMenu.has(id))),
      );
      return api.put(`/admin/today/employees/${encodeURIComponent(r.employeeCode)}/meals`, {
        date: view.date,
        lines,
        reason: reason.trim(),
      });
    },
    onSuccess: (_, r) => onDone(r.name),
  });
  const close = () => {
    save.reset();
    onClose();
  };
  const valid = reason.trim().length > 0;
  return (
    <AppDialog
      open={row !== null}
      onClose={close}
      title={t('adminToday.editTitle', { name: row?.name ?? '' })}
      description={t('adminToday.editDescription', { lock: formatVnTime(view.lockAt) })}
      footer={
        <>
          <Button variant="outline" onClick={close}>
            {t('common.cancel')}
          </Button>
          <Button form="meals-form" type="submit" disabled={!valid || save.isPending}>
            {save.isPending ? t('common.saving') : t('common.save')}
          </Button>
        </>
      }
    >
      <form
        id="meals-form"
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (row && valid) save.mutate(row);
        }}
      >
        {save.error ? <Alert variant="destructive">{apiErrorText(save.error)}</Alert> : null}
        <ul className="flex flex-col gap-2">
          {(menu?.items ?? []).map((item) => (
            <li key={item.id} className="flex items-center justify-between gap-3">
              <span>{item.displayName}</span>
              <QuantityStepper
                value={quantities[item.id] ?? 0}
                onChange={(v) => setQuantities((prev) => ({ ...prev, [item.id]: v }))}
                label={t('week.quantityOf', { dish: item.displayName })}
                increaseLabel={t('week.increase', { dish: item.displayName })}
                decreaseLabel={t('week.decrease', { dish: item.displayName })}
              />
            </li>
          ))}
        </ul>
        <div className="flex flex-col gap-2">
          <Label htmlFor="meals-reason">{t('adminToday.reason')}</Label>
          <Input
            id="meals-reason"
            required
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          {!valid ? (
            <p className="text-xs text-muted-foreground">{t('adminToday.reasonRequired')}</p>
          ) : null}
        </div>
      </form>
    </AppDialog>
  );
}
