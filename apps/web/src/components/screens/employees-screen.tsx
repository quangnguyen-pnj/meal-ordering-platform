'use client';

import type {
  EmployeeMappingView,
  MasterItemView,
  OrgFilterOption,
  ResetPinResult,
} from '@/lib/api-types';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Building2, KeyRound, MoreHorizontal, SlidersHorizontal } from 'lucide-react';
import { useRef, useState } from 'react';
import { toast } from 'sonner';
import { AppDialog } from '@/components/app-dialog';
import { returnFocus, useConfirm } from '@/components/confirm-dialog';
import { OrgLevelFilter } from '@/components/org-level-filter';
import { ResponsiveSelect } from '@/components/responsive-select';
import { Alert } from '@/components/ui/alert';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { PAGE_SIZE as PAGE, Pagination } from '@/components/ui/pagination';
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet';
import { LoadingRows } from '@/components/ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { useDebounced } from '@/hooks/use-debounced';
import { api } from '@/lib/api';
import { apiErrorText } from '@/lib/errors';
import { cn, formatRange } from '@/lib/utils';
import { t, vi } from '@/messages';

const NO_FILTERS = {
  q: '',
  orgUnitId: '',
  mealStatus: '',
  status: 'ACTIVE',
  floorId: '',
  canteenId: '',
  costCenterId: '',
};

interface Filters {
  q: string;
  /** Khối / Trung tâm / Phòng ban: employees at or below this node. */
  orgUnitId: string;
  mealStatus: string;
  status: string;
  floorId: string;
  canteenId: string;
  costCenterId: string;
}

/** The last two levels of an org path ("Tổ Nhẫn / Nhóm Nhẫn 1"); the full path goes in a tooltip. */
export function shortOrgPath(path: string): string {
  const parts = path.split(' / ');
  return parts.length <= 2 ? path : `… / ${parts.slice(-2).join(' / ')}`;
}

const roleText = (e: EmployeeMappingView) =>
  e.roles
    .filter((r) => r !== 'EMPLOYEE')
    .map((r) => vi.roles[r as keyof typeof vi.roles])
    .join(', ');

function MealUnit({ e }: { e: EmployeeMappingView }) {
  return e.mealStatus === 'OK' ? (
    <>{e.mealUnitName}</>
  ) : (
    <Badge variant="destructive">{vi.employees.mealStatuses[e.mealStatus]}</Badge>
  );
}

function Floor({ e }: { e: EmployeeMappingView }) {
  return (
    <>
      {e.floorName ?? t('common.none')}
      {e.floorSource === 'OVERRIDE' ? (
        <span className="block text-xs font-medium text-primary">
          {vi.employees.floorSource.OVERRIDE}
        </span>
      ) : null}
    </>
  );
}

function CostCenter({ e }: { e: EmployeeMappingView }) {
  return (
    <>
      {e.costCenterName ?? t('common.none')}
      {e.costCenterSource ? (
        <span className="block text-xs text-muted-foreground">
          {vi.employees.costCenterSource[e.costCenterSource]}
        </span>
      ) : null}
    </>
  );
}

/**
 * /admin/employees: scoped finder (dept admins see their units) with Khối / Trung tâm / Phòng
 * ban filters, meal unit and roles, the pick-up floor of one employee (HC admin, BR-63; cost
 * center follows the org tree) and PIN reset with a one-time temporary PIN.
 */
export function EmployeesScreen({ canManageOrg }: { canManageOrg: boolean }) {
  const qc = useQueryClient();
  const [confirm, confirmElement] = useConfirm();
  const [filters, setFilters] = useState<Filters>(NO_FILTERS);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [offset, setOffset] = useState(0);
  const [tempPin, setTempPin] = useState<{
    name: string;
    code: string;
    pin: string;
    trigger: HTMLElement | null;
  } | null>(null);
  // The row's menu button goes with each dialog, so focus can return to it afterwards.
  const [overrideFor, setOverrideFor] = useState<{
    employee: EmployeeMappingView;
    trigger: HTMLElement | null;
  } | null>(null);
  const [overrideFloor, setOverrideFloor] = useState('');
  const [overrideReason, setOverrideReason] = useState('');
  const q = useDebounced(filters.q.trim());

  const query = new URLSearchParams({ offset: String(offset), limit: String(PAGE) });
  for (const [k, v] of Object.entries({ ...filters, q })) if (v) query.set(k, v.trim());
  const list = useQuery({
    queryKey: ['/employees', query.toString()],
    queryFn: () => api.get<{ total: number; items: EmployeeMappingView[] }>(`/employees?${query}`),
    placeholderData: (prev) => prev,
  });
  const orgUnits = useQuery({
    queryKey: ['/employees/org-units'],
    queryFn: () => api.get<{ items: OrgFilterOption[] }>('/employees/org-units'),
  });
  const floors = useQuery({
    queryKey: ['/admin/master/floors'],
    queryFn: () => api.get<{ items: MasterItemView[] }>('/admin/master/floors'),
    enabled: canManageOrg,
  });
  const canteens = useQuery({
    queryKey: ['/admin/master/canteens'],
    queryFn: () => api.get<{ items: MasterItemView[] }>('/admin/master/canteens'),
    enabled: canManageOrg,
  });
  const costCenters = useQuery({
    queryKey: ['/admin/master/cost-centers'],
    queryFn: () => api.get<{ items: MasterItemView[] }>('/admin/master/cost-centers'),
    enabled: canManageOrg,
  });

  const resetPin = useMutation({
    mutationFn: ({ e }: { e: EmployeeMappingView; trigger: HTMLElement | null }) =>
      api.post<ResetPinResult>(`/employees/${encodeURIComponent(e.employeeCode)}/pin/reset`),
    onSuccess: (res, { e, trigger }) =>
      setTempPin({ name: e.name, code: e.employeeCode, pin: res.temporaryPin, trigger }),
  });
  const setOverride = useMutation({
    mutationFn: ({
      code,
      floorId,
      reason,
    }: {
      code: string;
      floorId: string | null;
      reason: string;
    }) => api.put(`/employees/${encodeURIComponent(code)}/floor-override`, { floorId, reason }),
    onSuccess: () => {
      setOverrideFor(null);
      toast.success(t('employees.overrideSaved'));
      void qc.invalidateQueries({ queryKey: ['/employees'] });
    },
  });

  async function askResetPin(e: EmployeeMappingView, trigger: HTMLElement | null) {
    const ok = await confirm({
      title: t('employees.resetPinTitle'),
      description: t('employees.resetPinConfirm', { name: e.name }),
      confirmLabel: t('employees.resetPin'),
      returnFocusTo: trigger,
    });
    if (ok) resetPin.mutate({ e, trigger });
  }

  const update = (patch: Partial<Filters>) => {
    setFilters((f) => ({ ...f, ...patch }));
    setOffset(0);
  };
  const items = list.data?.items ?? [];
  const total = list.data?.total ?? 0;
  const error = list.error ?? resetPin.error;

  // Filters that differ from the defaults, for the count on the phone's "Bộ lọc" button.
  const activeFilters = (Object.keys(NO_FILTERS) as Array<keyof Filters>).filter(
    (k) => k !== 'q' && filters[k] !== NO_FILTERS[k],
  ).length;

  const masterSelect = (
    prefix: string,
    id: keyof Filters,
    label: string,
    options: MasterItemView[] | undefined,
  ) => (
    <div className="flex w-full flex-col gap-2 sm:w-48">
      <Label htmlFor={`${prefix}-${id}`}>{label}</Label>
      <ResponsiveSelect
        id={`${prefix}-${id}`}
        value={filters[id]}
        onValueChange={(v) => update({ [id]: v })}
        options={[
          { value: '', label: t('common.all') },
          ...(options ?? []).map((o) => ({ value: o.id, label: `${o.code} · ${o.name}` })),
        ]}
      />
    </div>
  );

  // Every filter but the search; `prefix` keeps ids unique between the page and the phone sheet.
  const filterFields = (prefix: string) => (
    <>
      <OrgLevelFilter
        id={`${prefix}-org`}
        options={orgUnits.data?.items ?? []}
        value={filters.orgUnitId}
        onChange={(orgUnitId) => update({ orgUnitId })}
      />
      <div className="flex w-full flex-col gap-2 sm:w-56">
        <Label htmlFor={`${prefix}-meal`}>{t('employees.mealStatus')}</Label>
        <ResponsiveSelect
          id={`${prefix}-meal`}
          value={filters.mealStatus}
          onValueChange={(v) => update({ mealStatus: v })}
          options={[
            { value: '', label: t('common.all') },
            ...Object.entries(vi.employees.mealStatuses).map(([k, label]) => ({
              value: k,
              label,
            })),
          ]}
        />
      </div>
      <div className="flex w-full flex-col gap-2 sm:w-40">
        <Label htmlFor={`${prefix}-status`}>{t('employees.statusFilter')}</Label>
        <ResponsiveSelect
          id={`${prefix}-status`}
          value={filters.status}
          onValueChange={(v) => update({ status: v })}
          options={[
            { value: '', label: t('common.all') },
            { value: 'ACTIVE', label: vi.employees.status.ACTIVE },
            { value: 'LEFT', label: vi.employees.status.LEFT },
          ]}
        />
      </div>
      {canManageOrg ? (
        <>
          {masterSelect(prefix, 'floorId', t('org.floor'), floors.data?.items)}
          {masterSelect(prefix, 'canteenId', t('org.canteen'), canteens.data?.items)}
          {masterSelect(prefix, 'costCenterId', t('org.costCenter'), costCenters.data?.items)}
        </>
      ) : null}
    </>
  );

  const actions = (e: EmployeeMappingView) => (
    <RowActions
      employee={e}
      canManageOrg={canManageOrg}
      resetPending={resetPin.isPending}
      onOverride={(trigger) => {
        setOverride.reset();
        setOverrideFloor(e.floorOverrideId ?? '');
        setOverrideReason('');
        setOverrideFor({ employee: e, trigger });
      }}
      onResetPin={(trigger) => askResetPin(e, trigger)}
    />
  );

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('employees.title')}</CardTitle>
        <CardDescription>{t('employees.intro')}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        <div
          role="group"
          className="flex flex-wrap items-end gap-4"
          aria-label={t('employees.filters')}
        >
          <div className="flex min-w-0 flex-1 flex-col gap-2 md:w-64 md:flex-none">
            <Label htmlFor="f-q">{t('common.search')}</Label>
            <Input
              id="f-q"
              type="search"
              value={filters.q}
              onChange={(e) => update({ q: e.target.value })}
            />
          </div>
          {/* Phones: the other filters live in a sheet, so the list starts on the first screen. */}
          <Button
            variant="outline"
            className="md:hidden"
            aria-haspopup="dialog"
            onClick={() => setFiltersOpen(true)}
          >
            <SlidersHorizontal className="h-4 w-4" aria-hidden />
            {t('employees.filters')}
            {activeFilters > 0 ? (
              <Badge variant="default" className="ml-1 px-1.5 py-0 tabular-nums">
                {activeFilters}
              </Badge>
            ) : null}
          </Button>
          <div className="hidden md:contents">{filterFields('f')}</div>
        </div>

        <Sheet open={filtersOpen} onOpenChange={setFiltersOpen}>
          <SheetContent side="bottom" className="max-h-[85dvh] gap-5 rounded-t-xl p-5 md:hidden">
            <SheetHeader>
              <SheetTitle>{t('employees.filters')}</SheetTitle>
              <SheetDescription>{t('employees.filtersHint')}</SheetDescription>
            </SheetHeader>
            <div className="-mx-5 flex flex-col gap-3 overflow-y-auto px-5 sm:[&>div]:w-full">
              {filterFields('fm')}
            </div>
            <div className="grid grid-cols-2 gap-2">
              <Button
                variant="outline"
                disabled={activeFilters === 0}
                onClick={() => {
                  setFilters((f) => ({ ...NO_FILTERS, q: f.q }));
                  setOffset(0);
                }}
              >
                {t('employees.clearFilters')}
              </Button>
              <Button onClick={() => setFiltersOpen(false)}>
                {list.data
                  ? t('employees.showResults', {
                      count: new Intl.NumberFormat('vi-VN').format(total),
                    })
                  : t('common.done')}
              </Button>
            </div>
          </SheetContent>
        </Sheet>

        {error ? <Alert variant="destructive">{apiErrorText(error)}</Alert> : null}

        <p className="text-sm text-muted-foreground" aria-live="polite">
          {list.data ? formatRange(offset, items.length, total) : ' '}
        </p>

        {list.isLoading ? (
          <LoadingRows rows={8} />
        ) : (
          <>
            {/* Phones: one card per employee. */}
            <ul className="flex flex-col gap-3 md:hidden">
              {items.map((e) => (
                <li
                  key={e.employeeCode}
                  className={cn(
                    'rounded-lg bg-card p-4 text-sm shadow-block',
                    e.status === 'LEFT' && 'text-muted-foreground',
                  )}
                >
                  <div className="flex items-start gap-2">
                    <div className="min-w-0 flex-1">
                      <p className="font-medium">
                        {e.name}{' '}
                        <span className="font-mono text-xs text-muted-foreground">
                          {e.employeeCode}
                        </span>
                      </p>
                      <p className="text-xs text-muted-foreground">{e.orgPath}</p>
                    </div>
                    <div className="-mr-2 -mt-2 shrink-0">{actions(e)}</div>
                  </div>
                  {/* Small labels above values: long labels never squeeze the value column. */}
                  <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-3">
                    {/* The "no meal unit" badge needs the whole row. */}
                    <div className={cn(e.mealStatus !== 'OK' && 'col-span-2')}>
                      <dt className="text-xs text-muted-foreground">{t('org.mealUnit')}</dt>
                      <dd>
                        <MealUnit e={e} />
                      </dd>
                    </div>
                    <div>
                      <dt className="text-xs text-muted-foreground">{t('org.floor')}</dt>
                      <dd>
                        <Floor e={e} />
                      </dd>
                    </div>
                    <div className="col-span-2">
                      <dt className="text-xs text-muted-foreground">{t('org.costCenter')}</dt>
                      <dd>
                        <CostCenter e={e} />
                      </dd>
                    </div>
                    {roleText(e) ? (
                      <div className="col-span-2">
                        <dt className="text-xs text-muted-foreground">{t('employees.roles')}</dt>
                        <dd>{roleText(e)}</dd>
                      </div>
                    ) : null}
                  </dl>
                </li>
              ))}
            </ul>

            <Table containerClassName="hidden md:block" className="min-w-[56rem]">
              <TableHeader>
                <TableRow>
                  <TableHead>{t('employees.employee')}</TableHead>
                  <TableHead>{t('org.path')}</TableHead>
                  <TableHead>{t('org.mealUnit')}</TableHead>
                  <TableHead>{t('org.floor')}</TableHead>
                  <TableHead>{t('org.costCenter')}</TableHead>
                  <TableHead>{t('employees.roles')}</TableHead>
                  {/* Only the ⋯ button sits here, so the header stays out of the way. */}
                  <TableHead className="w-14">
                    <span className="sr-only">{t('common.actions')}</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((e) => (
                  <TableRow
                    key={e.employeeCode}
                    className={e.status === 'LEFT' ? 'text-muted-foreground' : undefined}
                  >
                    <TableCell className="whitespace-nowrap">
                      <span className="font-medium">{e.name}</span>
                      <span className="block font-mono text-xs text-muted-foreground">
                        {e.employeeCode}
                      </span>
                    </TableCell>
                    <TableCell className="min-w-36 max-w-44 text-xs">
                      <Tooltip>
                        <TooltipTrigger className="text-left">
                          {shortOrgPath(e.orgPath)}
                        </TooltipTrigger>
                        <TooltipContent>{e.orgPath}</TooltipContent>
                      </Tooltip>
                    </TableCell>
                    <TableCell className="whitespace-nowrap">
                      <MealUnit e={e} />
                    </TableCell>
                    <TableCell className="whitespace-nowrap">
                      <Floor e={e} />
                    </TableCell>
                    <TableCell className="min-w-44">
                      <CostCenter e={e} />
                    </TableCell>
                    <TableCell className="text-xs">{roleText(e)}</TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end">{actions(e)}</div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            {total === 0 ? (
              <p className="rounded-lg bg-muted/50 p-8 text-center text-sm text-muted-foreground">
                {t('employees.empty')}
              </p>
            ) : null}
          </>
        )}

        <Pagination
          page={Math.floor(offset / PAGE) + 1}
          pageCount={Math.ceil(total / PAGE)}
          offset={offset}
          count={items.length}
          total={total}
          onPageChange={(page) => setOffset((page - 1) * PAGE)}
        />
      </CardContent>

      {confirmElement}

      {/* An AlertDialog: the one-time PIN closes only with the button, never by a stray click. */}
      <AlertDialog open={tempPin !== null}>
        <AlertDialogContent onCloseAutoFocus={(ev) => returnFocus(ev, tempPin?.trigger)}>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('employees.temporaryPinTitle')}</AlertDialogTitle>
            <AlertDialogDescription>
              {tempPin ? `${tempPin.name} · ${tempPin.code}` : ''}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <p className="rounded-lg bg-muted py-4 text-center font-mono text-4xl font-semibold tracking-[0.3em]">
            {tempPin?.pin}
          </p>
          <Alert variant="warning">{t('employees.temporaryPinHint')}</Alert>
          <AlertDialogFooter>
            <AlertDialogAction onClick={() => setTempPin(null)}>
              {t('employees.temporaryPinDone')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AppDialog
        open={overrideFor !== null}
        onClose={() => setOverrideFor(null)}
        returnFocusTo={overrideFor?.trigger}
        title={t('employees.overrideTitle', { name: overrideFor?.employee.name ?? '' })}
        description={t('employees.overrideHint')}
      >
        <form
          id="override-form"
          className="flex flex-col gap-3"
          onSubmit={(e) => {
            e.preventDefault();
            if (!overrideFor) return;
            if (!overrideFor || setOverride.isPending) return;
            setOverride.mutate({
              code: overrideFor.employee.employeeCode,
              floorId: overrideFloor || null,
              reason: overrideReason.trim(),
            });
          }}
        >
          <Label htmlFor="override-floor">{t('org.floor')}</Label>
          <ResponsiveSelect
            id="override-floor"
            value={overrideFloor}
            onValueChange={setOverrideFloor}
            options={[
              { value: '', label: t('employees.clearOverride') },
              ...(floors.data?.items ?? [])
                .filter((f) => f.active)
                .map((f) => ({ value: f.id, label: `${f.code} · ${f.name}` })),
            ]}
          />
          <Label htmlFor="override-reason">{t('employees.overrideReason')}</Label>
          <Input
            id="override-reason"
            required
            maxLength={500}
            placeholder={t('employees.overrideReasonPlaceholder')}
            value={overrideReason}
            onChange={(e) => setOverrideReason(e.target.value)}
          />
          {setOverride.error ? (
            <Alert variant="destructive">{apiErrorText(setOverride.error)}</Alert>
          ) : null}
          <div className="flex flex-col-reverse gap-2 pt-2 sm:flex-row sm:justify-end">
            <Button type="button" variant="outline" onClick={() => setOverrideFor(null)}>
              {t('common.cancel')}
            </Button>
            <Button
              type="submit"
              disabled={
                setOverride.isPending ||
                !overrideReason.trim() ||
                (overrideFloor || null) === (overrideFor?.employee.floorOverrideId ?? null)
              }
            >
              {setOverride.isPending ? t('common.saving') : t('common.save')}
            </Button>
          </div>
        </form>
      </AppDialog>
    </Card>
  );
}

/** The row's "…" menu (shadcn/ui DropdownMenu). Its button is where focus returns afterwards. */
function RowActions({
  employee: e,
  canManageOrg,
  resetPending,
  onOverride,
  onResetPin,
}: {
  employee: EmployeeMappingView;
  canManageOrg: boolean;
  resetPending: boolean;
  onOverride: (trigger: HTMLElement | null) => void;
  onResetPin: (trigger: HTMLElement | null) => void;
}) {
  const trigger = useRef<HTMLButtonElement>(null);
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          ref={trigger}
          variant="ghost"
          size="icon"
          aria-label={t('employees.actionsFor', { name: e.name })}
        >
          <MoreHorizontal className="h-4 w-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuLabel className="max-w-56 truncate">{e.name}</DropdownMenuLabel>
        <DropdownMenuGroup>
          {canManageOrg ? (
            <DropdownMenuItem onSelect={() => onOverride(trigger.current)}>
              <Building2 aria-hidden />
              {t('employees.override')}
            </DropdownMenuItem>
          ) : null}
          <DropdownMenuItem disabled={resetPending} onSelect={() => onResetPin(trigger.current)}>
            <KeyRound aria-hidden />
            {t('employees.resetPin')}
          </DropdownMenuItem>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
