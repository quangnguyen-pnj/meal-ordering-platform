'use client';

import { addDays } from '@meal/shared';
import type {
  GridSaveResult,
  NotRegisteredView,
  OrgFilterOption,
  RegistrationGridView,
  RegistrationImportView,
  RegistrationProgressView,
} from '@/lib/api-types';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Clock, FolderInput, Info, Lock, Plus, Search } from 'lucide-react';
import { useEffect, useState } from 'react';
import { toast } from 'sonner';
import { AppDialog } from '@/components/app-dialog';
import { useConfirm } from '@/components/confirm-dialog';
import { DishName } from '@/components/dish-name';
import { ExcelImportDialog } from '@/components/excel-import-dialog';
import { OrgLevelFilter } from '@/components/org-level-filter';
import { ResponsiveSelect } from '@/components/responsive-select';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Pagination } from '@/components/ui/pagination';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { LoadingRows } from '@/components/ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { WeekNav } from '@/components/week-nav';
import { useDebounced } from '@/hooks/use-debounced';
import { usePagination } from '@/hooks/use-pagination';
import { ApiError, api } from '@/lib/api';
import { apiErrorText } from '@/lib/errors';
import {
  columnTotals,
  GRID_FILTERS,
  gridChanges,
  gridFilterGroups,
  gridValues,
  matchesGridSearch,
  searchKey,
  type GridFilter,
  type GridValues,
} from '@/lib/grid-draft';
import { cn, formatDate, formatVnTime } from '@/lib/utils';
import { t, vi } from '@/messages';
import { weekdayName, weekdayShort } from './day-meals';
import {
  CanteenPicker,
  UnitPicker,
  useRegistrationGrid,
  useRegistrationUnits,
} from './registration-admin-shared';

type SelectionErrorKey = keyof typeof vi.registrations.selectionErrors;

const isRegistrationQuery = (key: readonly unknown[]) =>
  String(key[0]).startsWith('/admin/registrations');

/**
 * /admin/registrations (plan 4.1, J2): progress by unit, the not-registered list (BR-21) and the
 * bulk-entry grid, usable on a 768 px tablet. Dept admins see their units, HC admins the factory.
 */
export function RegistrationsAdminScreen() {
  const units = useRegistrationUnits();
  const [weekStart, setWeekStart] = useState<string | null>(null);
  const home = units.data?.upcomingWeekStart;
  const week = weekStart ?? home ?? null;

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-col gap-1.5">
            <CardTitle>{t('registrations.title')}</CardTitle>
            <CardDescription>{t('registrations.intro')}</CardDescription>
          </div>
          {week ? <WeekNav weekStart={week} onChange={setWeekStart} homeWeekStart={home} /> : null}
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        {units.error ? <Alert variant="destructive">{apiErrorText(units.error)}</Alert> : null}
        {units.isLoading ? <LoadingRows rows={3} /> : null}
        {week ? (
          <Tabs defaultValue="progress">
            <TabsList aria-label={t('registrations.title')}>
              <TabsTrigger value="progress">{t('registrations.tabs.progress')}</TabsTrigger>
              <TabsTrigger value="notRegistered">
                {t('registrations.tabs.notRegistered')}
              </TabsTrigger>
              <TabsTrigger value="grid">{t('registrations.tabs.grid')}</TabsTrigger>
            </TabsList>
            <TabsContent value="progress">
              <ProgressTab weekStart={week} />
            </TabsContent>
            <TabsContent value="notRegistered">
              <NotRegisteredTab weekStart={week} />
            </TabsContent>
            <TabsContent value="grid">
              <GridTab weekStart={week} />
            </TabsContent>
          </Tabs>
        ) : null}
      </CardContent>
    </Card>
  );
}

function ProgressTab({ weekStart }: { weekStart: string }) {
  const query = useQuery({
    queryKey: ['/admin/registrations/progress', weekStart],
    queryFn: () =>
      api.get<RegistrationProgressView>(`/admin/registrations/progress?weekStart=${weekStart}`),
  });
  const { pageItems, pagination } = usePagination(query.data?.units ?? [], weekStart);
  if (query.error) return <Alert variant="destructive">{apiErrorText(query.error)}</Alert>;
  if (!query.data) return <LoadingRows />;
  if (query.data.units.length === 0) {
    return <p className="text-sm text-muted-foreground">{t('registrations.noUnits')}</p>;
  }
  return (
    <div className="flex flex-col gap-3">
      <Table className="min-w-[36rem]">
        <TableHeader>
          <TableRow>
            <TableHead>{t('registrations.mealUnit')}</TableHead>
            <TableHead className="text-right">{t('registrations.headcount')}</TableHead>
            <TableHead className="text-right">{t('registrations.registered')}</TableHead>
            <TableHead className="text-right">{t('registrations.notRegistered')}</TableHead>
            <TableHead className="text-right">{t('registrations.meals')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {pageItems.map((u) => {
            const pct = u.headcount > 0 ? Math.round((u.registered / u.headcount) * 100) : 0;
            return (
              <TableRow key={u.mealUnitId}>
                <TableCell>
                  <p className="font-medium">{u.name}</p>
                  <p className="text-xs text-muted-foreground">{u.pathName}</p>
                  {!u.published ? (
                    <Badge variant="warning" className="mt-1">
                      {t('registrations.notPublished')}
                    </Badge>
                  ) : null}
                </TableCell>
                <TableCell className="text-right tabular-nums">{u.headcount}</TableCell>
                <TableCell className="text-right tabular-nums">
                  {u.registered}{' '}
                  <span className="text-xs text-muted-foreground">
                    {t('registrations.progressPct', { pct })}
                  </span>
                </TableCell>
                <TableCell className="text-right tabular-nums">{u.notRegistered}</TableCell>
                <TableCell className="text-right tabular-nums">{u.meals}</TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
      <Pagination {...pagination} />
    </div>
  );
}

function NotRegisteredTab({ weekStart }: { weekStart: string }) {
  const [date, setDate] = useState('');
  const [orgUnitId, setOrgUnitId] = useState('');
  const [q, setQ] = useState('');
  const term = useDebounced(q.trim().toLocaleLowerCase('vi'), 200);
  const query = useQuery({
    queryKey: ['/admin/registrations/not-registered', weekStart, date, orgUnitId],
    queryFn: () => {
      const q = new URLSearchParams({ weekStart });
      if (date) q.set('date', date);
      if (orgUnitId) q.set('orgUnitId', orgUnitId);
      return api.get<NotRegisteredView>(`/admin/registrations/not-registered?${q}`);
    },
  });
  // Searched here: the API returns the whole list for the week, day and org filter.
  const matches = (query.data?.items ?? []).filter(
    (e) =>
      !term ||
      e.employeeCode.toLocaleLowerCase('vi').includes(term) ||
      e.name.toLocaleLowerCase('vi').includes(term),
  );
  const { pageItems, pagination } = usePagination(
    matches,
    `${weekStart}|${date}|${orgUnitId}|${term}`,
  );
  // Khối / Trung tâm / Phòng ban filter, within the caller's scope.
  const orgUnits = useQuery({
    queryKey: ['/admin/registrations/org-units'],
    queryFn: () => api.get<{ items: OrgFilterOption[] }>('/admin/registrations/org-units'),
  });
  const options = [
    { value: '', label: t('registrations.allWeek') },
    ...[0, 1, 2, 3, 4, 5, 6].map((i) => {
      const d = addDays(weekStart, i);
      return { value: d, label: `${weekdayName(d)} ${formatDate(d)}` };
    }),
  ];
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end gap-4">
        <div className="flex w-full flex-col gap-2 sm:w-56">
          <Label htmlFor="nr-q">{t('common.search')}</Label>
          <Input id="nr-q" type="search" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <div className="flex w-full flex-col gap-2 sm:w-56">
          <Label htmlFor="nr-day">{t('history.date')}</Label>
          <ResponsiveSelect id="nr-day" value={date} onValueChange={setDate} options={options} />
        </div>
        <OrgLevelFilter
          id="nr-org"
          options={orgUnits.data?.items ?? []}
          value={orgUnitId}
          onChange={setOrgUnitId}
        />
      </div>
      {query.error ? <Alert variant="destructive">{apiErrorText(query.error)}</Alert> : null}
      {query.isLoading ? <LoadingRows /> : null}
      {query.data && query.data.total === 0 ? (
        <p className="text-sm text-muted-foreground">{t('registrations.noOne')}</p>
      ) : null}
      {query.data && query.data.total > 0 ? (
        <>
          <p className="text-sm">{t('registrations.listTotal', { count: query.data.total })}</p>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('common.code')}</TableHead>
                <TableHead>{t('common.name')}</TableHead>
                <TableHead className="hidden md:table-cell">{t('registrations.unit')}</TableHead>
                <TableHead className="hidden sm:table-cell">
                  {t('registrations.mealUnit')}
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {pageItems.map((e) => (
                <TableRow key={e.employeeCode}>
                  <TableCell className="tabular-nums">{e.employeeCode}</TableCell>
                  <TableCell>
                    <p className="font-medium">{e.name}</p>
                    {/* Narrow screens: the unit columns fold under the name. */}
                    <p className="text-xs text-muted-foreground md:hidden">
                      <span className="sm:hidden">{e.mealUnitName} · </span>
                      {e.orgPath}
                    </p>
                  </TableCell>
                  <TableCell className="hidden text-sm text-muted-foreground md:table-cell">
                    {e.orgPath}
                  </TableCell>
                  <TableCell className="hidden sm:table-cell">{e.mealUnitName}</TableCell>
                </TableRow>
              ))}
              {matches.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={4} className="text-muted-foreground">
                    {t('registrations.noMatch')}
                  </TableCell>
                </TableRow>
              ) : null}
            </TableBody>
          </Table>
          <Pagination {...pagination} />
        </>
      ) : null}
    </div>
  );
}

function rowErrorsFrom(error: unknown): Record<string, string[]> {
  if (!(error instanceof ApiError) || !Array.isArray(error.details)) return {};
  const out: Record<string, string[]> = {};
  for (const d of error.details as Array<{ employeeCode?: string; code?: string }>) {
    if (!d.employeeCode || !d.code) continue;
    const label = vi.registrations.selectionErrors[d.code as SelectionErrorKey] ?? d.code;
    (out[d.employeeCode] ??= []).push(label);
  }
  return out;
}

// Frozen parts of the grid: they need a solid background, so tints are drawn with inset shadows.
const LINE_BOTTOM = 'shadow-[inset_0_-1px_0_hsl(var(--border))]';
const LINE_TOP = 'shadow-[inset_0_1px_0_hsl(var(--border))]';
const STICKY_COL = 'sticky left-0 w-40 min-w-40 max-w-56 sm:w-56 sm:min-w-56';
const COL_EDGE = 'shadow-[inset_-1px_0_0_hsl(var(--border))]';

function GridTab({ weekStart }: { weekStart: string }) {
  const qc = useQueryClient();
  const units = useRegistrationUnits();
  const [confirm, confirmDialog] = useConfirm();
  const [unitId, setUnitId] = useState('');
  const [canteenId, setCanteenId] = useState('');
  const [date, setDate] = useState('');
  const [values, setValues] = useState<GridValues>({});
  const [extraFor, setExtraFor] = useState<string | null>(null);
  const [importOpen, setImportOpen] = useState(false);
  const [q, setQ] = useState('');
  const term = useDebounced(searchKey(q), 150);
  const [filter, setFilter] = useState<GridFilter>('all');
  // Bumped when a filter is picked or the values are reset (new grid, other day, discard).
  const [snapshot, setSnapshot] = useState(0);
  const grid = useRegistrationGrid(unitId, weekStart, canteenId);
  const view = grid.data;

  // A new grid (unit, week, save) resets the values; switching days goes through pickDay.
  useEffect(() => {
    if (!view || view.state !== 'PUBLISHED') return;
    const d = view.days.some((x) => x.date === date) ? date : (view.days[0]?.date ?? '');
    setDate(d);
    setValues(gridValues(view, d));
    setSnapshot((n) => n + 1);
  }, [view]);

  const day = view?.days.find((d) => d.date === date);
  const changes = view && day ? gridChanges(view, date, values) : { rows: [], invalid: {} };
  const invalidCount = Object.keys(changes.invalid).length;
  const dirty = changes.rows.length > 0 || invalidCount > 0;

  const save = useMutation({
    mutationFn: () =>
      api.put<GridSaveResult>(
        `/admin/registrations/units/${encodeURIComponent(unitId)}/days/${date}`,
        { canteenId: view!.canteenId, rows: changes.rows },
      ),
    onSuccess: async (res) => {
      toast.success(
        t('registrations.savedDay', { changed: res.changedEmployees, meals: res.meals }),
      );
      await qc.invalidateQueries({ predicate: (q) => isRegistrationQuery(q.queryKey) });
    },
  });
  const serverRowErrors = rowErrorsFrom(save.error);
  const groups =
    view && day ? gridFilterGroups(view, date, values, changes, serverRowErrors) : null;

  // A status filter keeps the rows it matched when it was picked, so a row does not vanish while
  // someone types in it. It is worked out again on a new pick, day or grid; an empty one falls
  // back to everyone.
  const filterKey = `${filter}|${snapshot}`;
  const [members, setMembers] = useState<{ key: string; codes: Set<string> | null }>({
    key: '',
    codes: null,
  });
  if (groups && members.key !== filterKey) {
    const codes = filter === 'all' ? [] : groups[filter];
    if (filter !== 'all' && codes.length === 0) setFilter('all');
    else setMembers({ key: filterKey, codes: filter === 'all' ? null : new Set(codes) });
  }

  function pickFilter(next: GridFilter) {
    setFilter(next);
    setSnapshot((n) => n + 1);
  }

  async function pickDay(d: string) {
    if (d === date) return;
    if (
      dirty &&
      !(await confirm({
        title: t('registrations.leaveConfirm'),
        confirmLabel: t('registrations.discard'),
      }))
    )
      return;
    setDate(d);
    save.reset();
    if (view) setValues(gridValues(view, d));
    setSnapshot((n) => n + 1);
  }

  function moveDown(e: React.KeyboardEvent<HTMLInputElement>, row: number, col: number) {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    const next = document.querySelector<HTMLInputElement>(`[data-cell="${row + 1}:${col}"]`);
    next?.focus({ preventScroll: true });
    // Nearest, inside the grid's scroll-padding, so the sticky header and totals never cover it.
    next?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    next?.select();
  }

  const totals = view && day ? columnTotals(view, date, values) : {};
  const editable = day?.window === 'ON_BEHALF';
  // Row numbers stay those of the whole unit while filtering.
  const shown = (view?.rows ?? [])
    .map((row, index) => ({ row, index }))
    .filter(
      ({ row }) =>
        (!members.codes || members.codes.has(row.employeeCode)) && matchesGridSearch(row, term),
    );
  const filtered = !!view && shown.length !== view.rows.length;
  const errorRows = groups?.errors.length ?? 0;

  return (
    <div className="flex flex-col gap-4">
      {confirmDialog}
      <div className="flex flex-wrap items-end gap-4">
        <UnitPicker
          id="grid-unit"
          units={units.data?.items ?? []}
          value={unitId}
          onChange={(id) => {
            setUnitId(id);
            setCanteenId('');
            setQ('');
            setFilter('all');
            save.reset();
          }}
        />
        <CanteenPicker id="grid-canteen" grid={view} onChange={setCanteenId} />
        {view?.state === 'PUBLISHED' && view.days.some((d) => d.window === 'ON_BEHALF') ? (
          <Button variant="outline" className="ml-auto" onClick={() => setImportOpen(true)}>
            <FolderInput className="h-5 w-5 text-primary" aria-hidden />
            {t('registrations.import.open')}
          </Button>
        ) : null}
      </div>

      {view?.state === 'PUBLISHED' ? (
        <WeekImport
          open={importOpen}
          onClose={() => setImportOpen(false)}
          unitId={unitId}
          weekStart={weekStart}
          canteenId={view.canteenId!}
          unsavedDay={dirty}
          onApplied={async () => {
            await qc.invalidateQueries({ predicate: (q) => isRegistrationQuery(q.queryKey) });
          }}
        />
      ) : null}

      {grid.error ? <Alert variant="destructive">{apiErrorText(grid.error)}</Alert> : null}
      {grid.isLoading ? <LoadingRows /> : null}
      {view?.state === 'NOT_PUBLISHED' ? (
        <Alert>{t('week.notPublished', { canteen: view.canteenName ?? '' })}</Alert>
      ) : null}
      {view?.state === 'NO_EMPLOYEES' ? <Alert>{t('registrations.noEmployees')}</Alert> : null}
      {view && (view.skipped.otherCanteen > 0 || view.skipped.cannotRegister > 0) ? (
        <p className="text-xs text-muted-foreground">
          {t('registrations.skipped', {
            other: view.skipped.otherCanteen,
            cannot: view.skipped.cannotRegister,
          })}
        </p>
      ) : null}

      {view?.state === 'PUBLISHED' && day && groups ? (
        <>
          {/* Days on their own row. Then one toolbar: search and row filter (side by side, also on
              phones) and the day's window, whose entry hint opens from the info button. */}
          <GridDayTabs days={view.days} value={date} onPick={(d) => void pickDay(d)} />

          <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
            <div className="flex w-full min-w-0 gap-2 lg:w-auto">
              <div className="relative min-w-0 flex-1 lg:w-64 lg:flex-none">
                <Search
                  className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground"
                  aria-hidden
                />
                <Input
                  type="search"
                  value={q}
                  onChange={(e) => setQ(e.target.value)}
                  placeholder={t('registrations.gridSearch')}
                  aria-label={t('registrations.gridSearch')}
                  className="pl-9"
                />
              </div>
              <ResponsiveSelect
                aria-label={t('registrations.gridFilter')}
                value={filter}
                onValueChange={(v) => pickFilter(v as GridFilter)}
                className="w-40 shrink-0 sm:w-52"
                options={GRID_FILTERS.map((f) => ({
                  value: f,
                  label: t(`registrations.gridFilters.${f}`, { count: groups[f].length }),
                  disabled: f !== 'all' && f !== filter && groups[f].length === 0,
                }))}
              />
            </div>
            {filtered ? (
              <p className="flex items-center gap-2 text-sm tabular-nums text-muted-foreground">
                <span aria-live="polite">
                  {t('registrations.gridShown', { shown: shown.length, total: view.rows.length })}
                </span>
                <Button
                  variant="link"
                  size="sm"
                  className="h-auto px-0"
                  onClick={() => {
                    setQ('');
                    pickFilter('all');
                  }}
                >
                  {t('registrations.clearFilter')}
                </Button>
              </p>
            ) : null}
            <div
              className={cn(
                'flex min-h-9 items-center gap-1.5 text-sm lg:ml-auto',
                editable ? 'text-muted-foreground' : 'rounded-md bg-warning/15 px-2.5 py-1',
              )}
            >
              {editable ? (
                <Clock className="h-4 w-4 shrink-0" aria-hidden />
              ) : (
                <Lock className="h-4 w-4 shrink-0" aria-hidden />
              )}
              <span>
                {t(`registrations.windows.${day.window}`, { lock: formatVnTime(day.lockAt) })}
              </span>
              {editable ? (
                <Popover>
                  <PopoverTrigger asChild>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-8 w-8 shrink-0"
                      aria-label={t('registrations.gridHintLabel')}
                    >
                      <Info className="h-4 w-4" aria-hidden />
                    </Button>
                  </PopoverTrigger>
                  <PopoverContent align="start" className="w-72 text-sm">
                    {t('registrations.gridHint')}
                  </PopoverContent>
                </Popover>
              ) : null}
            </div>
          </div>

          {save.error && Object.keys(serverRowErrors).length === 0 ? (
            <Alert variant="destructive">{apiErrorText(save.error)}</Alert>
          ) : null}

          {/* Scrolls inside a screen-high box, so the dish header, the names and the totals stay
              in view; scroll-padding keeps a focused cell clear of them. */}
          <Table containerClassName="isolate max-h-[max(22rem,calc(100dvh_-_11rem))] overflow-auto scroll-pb-16 scroll-pl-40 scroll-pt-20 sm:scroll-pl-56">
            <TableHeader className="bg-muted">
              <TableRow className="hover:bg-transparent">
                <TableHead
                  className={cn(STICKY_COL, 'top-0 z-30 bg-muted px-2', LINE_BOTTOM, COL_EDGE)}
                >
                  <span className="hidden w-7 pr-2 text-right sm:inline-block">#</span>
                  {t('registrations.employee')}
                </TableHead>
                {day.items.map((i) => (
                  <TableHead
                    key={i.id}
                    className={cn(
                      'sticky top-0 z-20 min-w-20 max-w-28 whitespace-normal bg-muted px-2 py-2 text-center normal-case tracking-normal',
                      LINE_BOTTOM,
                    )}
                  >
                    <DishName type={i.type} displayName={i.displayName} />
                  </TableHead>
                ))}
                <TableHead
                  className={cn('sticky top-0 z-20 bg-muted px-2 text-center', LINE_BOTTOM)}
                >
                  {t('registrations.total')}
                </TableHead>
              </TableRow>
            </TableHeader>

            <TableBody>
              {shown.map(({ row, index }, r) => {
                const cell = row.cells[date]!;
                const invalid = changes.invalid[row.employeeCode] ?? [];
                const errors = serverRowErrors[row.employeeCode] ?? [];
                const rowTotal = day.items.reduce(
                  (s, i) => s + (Number(values[row.employeeCode]?.[i.id]) || 0),
                  0,
                );
                const forecast = cell.forecast
                  ? Object.values(cell.forecast).reduce((s, n) => s + n, 0)
                  : null;
                const hasError = invalid.length > 0 || errors.length > 0;
                return (
                  <TableRow
                    key={row.employeeCode}
                    className={cn(
                      'group hover:bg-transparent focus-within:bg-primary/5',
                      hasError && 'bg-destructive/5 focus-within:bg-destructive/5',
                    )}
                  >
                    <TableCell
                      className={cn(
                        STICKY_COL,
                        'z-10 bg-card px-2 py-1',
                        hasError
                          ? 'shadow-[inset_-1px_0_0_hsl(var(--border)),inset_0_0_0_9999px_hsl(var(--destructive)/0.05)]'
                          : cn(
                              COL_EDGE,
                              'group-focus-within:shadow-[inset_-1px_0_0_hsl(var(--border)),inset_0_0_0_9999px_hsl(var(--primary)/0.05)]',
                            ),
                      )}
                    >
                      <div className="flex items-start gap-1">
                        <span className="hidden w-7 shrink-0 pr-2 pt-0.5 text-right text-xs tabular-nums text-muted-foreground sm:block">
                          {index + 1}
                        </span>
                        <div className="min-w-0 flex-1">
                          <p className="truncate font-medium" title={row.name}>
                            {row.name}
                          </p>
                          <div className="flex flex-wrap items-center gap-x-1.5 gap-y-0.5 text-xs text-muted-foreground">
                            <span className="tabular-nums">
                              {row.employeeCode}
                              {forecast !== null
                                ? ` · ${t('registrations.forecastShort', { count: forecast })}`
                                : ''}
                            </span>
                            {cell.blocked ? (
                              <Badge variant="outline" className="px-1.5 leading-4">
                                {t('registrations.blocked')}
                              </Badge>
                            ) : null}
                            {cell.extra ? (
                              <Badge variant="secondary" className="px-1.5 leading-4">
                                {t('registrations.extra')}
                              </Badge>
                            ) : null}
                            {cell.needsReselect > 0 ? (
                              <Badge variant="warning" className="px-1.5 leading-4">
                                {t('registrations.needsReselect', { count: cell.needsReselect })}
                              </Badge>
                            ) : null}
                          </div>
                          {invalid.length > 0 ? (
                            <span className="sr-only">{t('registrations.invalidCell')}</span>
                          ) : null}
                          {errors.length > 0 ? (
                            <p className="text-xs text-destructive">
                              {t('registrations.rowError', { errors: errors.join(', ') })}
                            </p>
                          ) : null}
                        </div>
                        {day.window === 'EXTRA' && !cell.blocked ? (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <Button
                                variant="ghost"
                                size="icon"
                                className="h-8 w-8 shrink-0 text-primary"
                                aria-label={`${t('registrations.addExtra')}: ${row.name}`}
                                onClick={() => setExtraFor(row.employeeCode)}
                              >
                                <Plus className="h-4 w-4" aria-hidden />
                              </Button>
                            </TooltipTrigger>
                            <TooltipContent>{t('registrations.addExtra')}</TooltipContent>
                          </Tooltip>
                        ) : null}
                      </div>
                    </TableCell>
                    {day.items.map((i, c) => (
                      <TableCell key={i.id} className="px-1 py-1 text-center">
                        <Input
                          data-cell={`${r}:${c}`}
                          inputMode="numeric"
                          aria-label={`${row.name} · ${i.displayName}`}
                          aria-invalid={invalid.includes(i.id)}
                          title={
                            invalid.includes(i.id) ? t('registrations.invalidCell') : undefined
                          }
                          disabled={!editable || cell.blocked}
                          value={values[row.employeeCode]?.[i.id] ?? ''}
                          onKeyDown={(e) => moveDown(e, r, c)}
                          onChange={(e) => {
                            const v = e.target.value;
                            setValues((prev) => ({
                              ...prev,
                              [row.employeeCode]: { ...prev[row.employeeCode], [i.id]: v },
                            }));
                          }}
                          className="mx-auto w-14 px-1 text-center tabular-nums md:h-9"
                        />
                      </TableCell>
                    ))}
                    <TableCell className="px-2 py-1 text-center font-medium tabular-nums">
                      {rowTotal}
                    </TableCell>
                  </TableRow>
                );
              })}
              {shown.length === 0 ? (
                <TableRow className="hover:bg-transparent">
                  <TableCell colSpan={day.items.length + 2} className="text-muted-foreground">
                    {t('registrations.gridNoMatch')}
                  </TableCell>
                </TableRow>
              ) : null}
            </TableBody>

            {/* Totals are always the whole unit, whatever the search or filter shows. */}
            <tfoot className="font-semibold">
              <tr>
                <td
                  className={cn(
                    STICKY_COL,
                    'bottom-0 z-30 bg-muted px-2 py-2.5',
                    'shadow-[inset_-1px_0_0_hsl(var(--border)),inset_0_1px_0_hsl(var(--border))]',
                  )}
                >
                  <span className="sm:pl-8">{t('registrations.total')}</span>
                  <span className="ml-2 text-xs font-normal text-muted-foreground">
                    {t('registrations.gridPeople', { count: view.rows.length })}
                  </span>
                </td>
                {day.items.map((i) => (
                  <td
                    key={i.id}
                    className={cn(
                      'sticky bottom-0 z-20 bg-muted px-2 py-2.5 text-center tabular-nums',
                      LINE_TOP,
                    )}
                  >
                    {totals[i.id] ?? 0}
                  </td>
                ))}
                <td
                  className={cn(
                    'sticky bottom-0 z-20 bg-muted px-2 py-2.5 text-center tabular-nums',
                    LINE_TOP,
                  )}
                >
                  {Object.values(totals).reduce((s, n) => s + n, 0)}
                </td>
              </tr>
            </tfoot>
          </Table>

          <ExtraDialog
            view={view}
            date={date}
            employeeCode={extraFor}
            onClose={() => setExtraFor(null)}
            onSaved={async () => {
              setExtraFor(null);
              toast.success(t('registrations.extraSaved'));
              await qc.invalidateQueries({ predicate: (q) => isRegistrationQuery(q.queryKey) });
            }}
          />

          {editable && dirty ? (
            <div className="sticky bottom-0 z-20 -mx-5 flex flex-wrap items-center justify-between gap-3 bg-card px-5 py-3 sm:-mx-6 sm:px-6 shadow-[0_-6px_16px_-6px_rgb(41_37_36/0.12)]">
              <p className="flex flex-wrap items-center gap-x-2 text-sm" role="status">
                <span>
                  {invalidCount > 0
                    ? t('registrations.invalidRows', { count: invalidCount })
                    : errorRows > 0
                      ? t('registrations.saveErrorRows', { count: errorRows })
                      : t('registrations.unsaved')}
                </span>
                <Button
                  variant="link"
                  size="sm"
                  className="h-auto px-0"
                  onClick={() => pickFilter(errorRows > 0 ? 'errors' : 'changed')}
                >
                  {errorRows > 0 ? t('registrations.viewErrors') : t('registrations.viewChanged')}
                </Button>
              </p>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  disabled={save.isPending}
                  onClick={() => {
                    setValues(gridValues(view, date));
                    save.reset();
                    setSnapshot((n) => n + 1);
                  }}
                >
                  {t('registrations.discard')}
                </Button>
                <Button
                  disabled={changes.rows.length === 0 || invalidCount > 0 || save.isPending}
                  onClick={() => save.mutate()}
                >
                  {save.isPending
                    ? t('common.saving')
                    : t('registrations.saveDay', { day: weekdayName(date) })}
                </Button>
              </div>
            </div>
          ) : null}
        </>
      ) : null}
    </div>
  );
}

/**
 * Day tabs of the grid. Manual activation: a tab changes the day only when clicked or on
 * Enter/Space, not when it merely gets focus. With Radix's default (automatic), the unsaved-changes
 * confirm reopened after "Hủy", because closing it gives focus back to the clicked tab, and that
 * focus switched the day again.
 */
export function GridDayTabs({
  days,
  value,
  onPick,
}: {
  days: RegistrationGridView['days'];
  value: string;
  onPick: (date: string) => void;
}) {
  return (
    <Tabs value={value} onValueChange={onPick} activationMode="manual" className="min-w-0">
      <TabsList aria-label={t('registrations.tabs.grid')}>
        {days.map((d) => (
          <TabsTrigger key={d.date} value={d.date} className="gap-1 px-2.5 sm:px-3">
            {d.window !== 'ON_BEHALF' ? (
              <Lock className="h-3.5 w-3.5 opacity-70" aria-hidden />
            ) : null}
            <span className="sm:hidden">{weekdayShort(d.date)}</span>
            <span className="hidden sm:inline">{weekdayName(d.date)}</span>
            <span className="tabular-nums">{formatDate(d.date).slice(0, 5)}</span>
            {d.window !== 'ON_BEHALF' ? (
              <span className="sr-only">{t('registrations.dayLocked')}</span>
            ) : null}
          </TabsTrigger>
        ))}
      </TabsList>
    </Tabs>
  );
}

/** BR-25: extra meals after the lock, with a required reason. */
function ExtraDialog({
  view,
  date,
  employeeCode,
  onClose,
  onSaved,
}: {
  view: RegistrationGridView;
  date: string;
  employeeCode: string | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const day = view.days.find((d) => d.date === date);
  const row = view.rows.find((r) => r.employeeCode === employeeCode);
  const [menuItemId, setMenuItemId] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [reason, setReason] = useState('');
  const add = useMutation({
    mutationFn: () =>
      api.post(`/admin/registrations/employees/${encodeURIComponent(employeeCode!)}/extras`, {
        date,
        menuItemId: menuItemId || day!.items[0]!.id,
        quantity: Number(quantity),
        reason,
      }),
    onSuccess: () => {
      setQuantity('1');
      setReason('');
      onSaved();
    },
  });
  const valid = /^[1-9]\d*$/.test(quantity.trim()) && reason.trim().length > 0;

  return (
    <AppDialog
      open={employeeCode !== null && !!row && !!day}
      onClose={() => {
        add.reset();
        onClose();
      }}
      title={t('registrations.extraTitle', { name: row?.name ?? '', date: formatDate(date) })}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button form="extra-form" type="submit" disabled={!valid || add.isPending}>
            {add.isPending ? t('common.saving') : t('registrations.addExtra')}
          </Button>
        </>
      }
    >
      <form
        id="extra-form"
        className="flex flex-col gap-5"
        onSubmit={(e) => {
          e.preventDefault();
          if (valid) add.mutate();
        }}
      >
        {add.error ? <Alert variant="destructive">{apiErrorText(add.error)}</Alert> : null}
        <div className="flex flex-col gap-2">
          <Label htmlFor="extra-dish">{t('registrations.extraDish')}</Label>
          <ResponsiveSelect
            id="extra-dish"
            value={menuItemId || day?.items[0]?.id || ''}
            onValueChange={setMenuItemId}
            options={(day?.items ?? []).map((i) => ({ value: i.id, label: i.displayName }))}
          />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor="extra-qty">{t('registrations.extraQuantity')}</Label>
          <Input
            id="extra-qty"
            inputMode="numeric"
            className="w-28"
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
          />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor="extra-reason">{t('registrations.extraReason')}</Label>
          <Input
            id="extra-reason"
            value={reason}
            placeholder={t('registrations.extraReasonPlaceholder')}
            onChange={(e) => setReason(e.target.value)}
          />
        </div>
      </form>
    </AppDialog>
  );
}

/**
 * The unit's week as an Excel file (2026-10-23): download it filled with the current quantities,
 * edit it, check it, then save every changed day at once. The grid's rules apply (BR-21).
 */
function WeekImport({
  open,
  onClose,
  unitId,
  weekStart,
  canteenId,
  unsavedDay,
  onApplied,
}: {
  open: boolean;
  onClose: () => void;
  unitId: string;
  weekStart: string;
  canteenId: string;
  unsavedDay: boolean;
  onApplied: () => Promise<void>;
}) {
  const base = `/admin/registrations/units/${encodeURIComponent(unitId)}`;
  const query = `weekStart=${weekStart}&canteenId=${encodeURIComponent(canteenId)}`;
  return (
    <ExcelImportDialog<RegistrationImportView>
      open={open}
      onClose={onClose}
      title={t('registrations.import.title')}
      hint={`${t('registrations.import.hint')}${unsavedDay ? ` ${t('registrations.unsaved')}` : ''}`}
      template={{
        path: `${base}/import-template?${query}`,
        fileName: `dang-ky-${weekStart}.xlsx`,
      }}
      labels={{
        template: t('registrations.import.template'),
        file: t('registrations.import.file'),
        check: t('registrations.import.check'),
      }}
      upload={(file, apply) =>
        api.upload<RegistrationImportView>(`${base}/import?${query}&apply=${apply}`, file)
      }
      summarize={(r) =>
        r.status === 'REJECTED'
          ? {
              text: t('registrations.import.rejected', { count: r.errors.length }),
              errors: r.errors.map((e) =>
                t('registrations.import.rowError', {
                  row: e.rowNumber,
                  error: vi.registrations.import.errors[e.code],
                  value: e.value || t('common.none'),
                }),
              ),
              applyLabel: null,
            }
          : {
              text:
                r.status === 'APPLIED'
                  ? t('registrations.import.applied', {
                      employees: r.changedEmployees,
                      days: r.changedDays,
                    })
                  : r.changedDays === 0
                    ? t('registrations.import.nothing')
                    : t('registrations.import.preview', {
                        employees: r.changedEmployees,
                        days: r.changedDays,
                      }),
              errors: [],
              applyLabel:
                r.changedDays > 0 ? t('registrations.import.apply', { days: r.changedDays }) : null,
            }
      }
      onApplied={(r) => {
        toast.success(
          t('registrations.import.applied', { employees: r.changedEmployees, days: r.changedDays }),
        );
        void onApplied();
      }}
    />
  );
}
