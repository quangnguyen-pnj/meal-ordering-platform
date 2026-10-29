'use client';

import type { DessertView, DishTypeView, DishView, LibraryImportView } from '@/lib/api-types';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FolderInput } from 'lucide-react';
import { useEffect, useState } from 'react';
import { toast } from 'sonner';
import { DishName, DishTypeTag } from '@/components/dish-name';
import { ExcelImportDialog } from '@/components/excel-import-dialog';
import { ResponsiveSelect } from '@/components/responsive-select';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import { AppDialog } from '@/components/app-dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Pagination } from '@/components/ui/pagination';
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
import { usePagination } from '@/hooks/use-pagination';
import { api, ApiError } from '@/lib/api';
import { apiErrorText } from '@/lib/errors';
import { t, vi } from '@/messages';

type Tab = 'main' | 'desserts' | 'types';
type Editing = { id: string | null; name: string; typeId: string };

interface CanteensResponse {
  items: Array<{ id: string; code: string; name: string }>;
}

/** Duplicate names get their own message: the bug was "a dish can be added twice". */
function saveErrorText(error: unknown): string {
  if (error instanceof ApiError && error.code === 'CONFLICT' && Array.isArray(error.details)) {
    const message = (error.details[0] as { message?: string } | undefined)?.message;
    if (message === 'DUPLICATE_ARCHIVED') return t('dishes.duplicateArchived');
    if (message === 'DUPLICATE') return t('dishes.duplicate');
  }
  return apiErrorText(error);
}

/**
 * /admin/dishes (BR-10): each canteen's main dishes and desserts (add, edit, archive, restore,
 * Excel import), and the dish types they use (master data with a colour, 2026-10-23).
 */
export function DishesScreen() {
  const [tab, setTab] = useState<Tab>('main');
  const canteens = useQuery({
    queryKey: ['menu-canteens'],
    queryFn: () => api.get<CanteensResponse>('/admin/menu/canteens'),
  });
  const [canteenId, setCanteenId] = useState<string | null>(null);
  useEffect(() => {
    if (canteens.data && !canteenId) setCanteenId(canteens.data.items[0]?.id ?? null);
  }, [canteens.data, canteenId]);
  const canteen = canteens.data?.items.find((c) => c.id === canteenId);

  return (
    <Card>
      <CardHeader className="gap-4">
        <div className="flex flex-col gap-1.5">
          <CardTitle>{t('dishes.title')}</CardTitle>
          <CardDescription>{t('dishes.intro')}</CardDescription>
        </div>
      </CardHeader>

      <CardContent>
        {canteens.error ? (
          <Alert variant="destructive">{apiErrorText(canteens.error)}</Alert>
        ) : null}

        {canteens.data?.items.length === 0 ? <Alert>{t('menu.noCanteen')}</Alert> : null}

        <Tabs value={tab} onValueChange={(v) => setTab(v as Tab)}>
          <TabsList aria-label={t('dishes.title')}>
            <TabsTrigger value="main">{t('dishes.tabs.main')}</TabsTrigger>
            <TabsTrigger value="desserts">{t('dishes.tabs.desserts')}</TabsTrigger>
            <TabsTrigger value="types">{t('dishes.tabs.types')}</TabsTrigger>
          </TabsList>

          {canteens.data && canteens.data.items.length > 0 && tab !== 'types' ? (
            <div className="flex w-full flex-col gap-2 sm:w-60 mt-5">
              <Label htmlFor="dish-canteen">{t('dishes.canteen')}</Label>
              <ResponsiveSelect
                id="dish-canteen"
                value={canteenId ?? ''}
                onValueChange={setCanteenId}
                options={canteens.data.items.map((c) => ({ value: c.id, label: c.name }))}
              />
            </div>
          ) : null}

          <TabsContent value="main">
            {canteen ? <MainDishes key={canteen.id} canteen={canteen} /> : null}
          </TabsContent>
          <TabsContent value="desserts">
            {canteen ? <Desserts key={canteen.id} canteen={canteen} /> : null}
          </TabsContent>
          <TabsContent value="types">
            <DishTypes />
          </TabsContent>
        </Tabs>
      </CardContent>
    </Card>
  );
}

function useLibrary<T>(path: string, canteenId: string, includeArchived: boolean) {
  return useQuery({
    queryKey: [path, canteenId, includeArchived],
    queryFn: () =>
      api.get<{ items: T[] }>(`${path}?canteenId=${canteenId}&includeArchived=${includeArchived}`),
  });
}

export function useDishTypes() {
  return useQuery({
    queryKey: ['/admin/dish-types'],
    queryFn: () => api.get<{ items: DishTypeView[] }>('/admin/dish-types'),
  });
}

/** Import a library file into the chosen canteen; the toolbar button that opens it. */
function LibraryImport({ canteen }: { canteen: { id: string; name: string } }) {
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>
        <FolderInput className="h-4 w-4" aria-hidden />
        {t('dishes.import.open')}
      </Button>

      <ExcelImportDialog<LibraryImportView>
        open={open}
        onClose={() => setOpen(false)}
        title={t('dishes.import.title')}
        hint={t('dishes.import.hint', { canteen: canteen.name })}
        template={{ path: '/admin/dish-library/template', fileName: 'mau-thu-vien-mon.xlsx' }}
        labels={{
          template: t('dishes.import.template'),
          file: t('dishes.import.file'),
          check: t('dishes.import.check'),
        }}
        upload={(file, apply) =>
          api.upload<LibraryImportView>(
            `/admin/dish-library/import?canteenId=${canteen.id}&apply=${apply}`,
            file,
          )
        }
        summarize={(r) =>
          r.status === 'REJECTED'
            ? {
                text: t('dishes.import.rejected', { count: r.errors.length }),
                errors: r.errors.map((e) =>
                  t('dishes.import.rowError', {
                    row: e.rowNumber,
                    error: vi.dishes.import.errors[e.code],
                    value: e.value || t('common.none'),
                  }),
                ),
                applyLabel: null,
              }
            : {
                text:
                  r.status === 'APPLIED'
                    ? t('dishes.import.applied', { count: r.toCreate.length })
                    : r.toCreate.length === 0
                      ? t('dishes.import.nothing', { skipped: r.alreadyInLibrary })
                      : t('dishes.import.preview', {
                          create: r.toCreate.length,
                          skipped: r.alreadyInLibrary,
                        }),
                errors: r.toCreate.map(
                  (row) =>
                    `${vi.dishes.import.kinds[row.kind]}${row.typeCode ? ` · ${row.typeCode}` : ''}: ${row.name}`,
                ),
                applyLabel:
                  r.toCreate.length > 0
                    ? t('dishes.import.apply', { count: r.toCreate.length })
                    : null,
              }
        }
        onApplied={(r) => {
          toast.success(t('dishes.import.applied', { count: r.toCreate.length }));
          void qc.invalidateQueries({ queryKey: ['/admin/dishes'] });
          void qc.invalidateQueries({ queryKey: ['/admin/desserts'] });
          void qc.invalidateQueries({ queryKey: ['/admin/dish-types'] });
        }}
      />
    </>
  );
}

/** A name search box over the library, filtered here: the list is already loaded whole. */
function NameSearch({
  id,
  value,
  onChange,
}: {
  id: string;
  value: string;
  onChange: (v: string) => void;
}) {
  return (
    <div className="flex w-full flex-col gap-2 sm:w-56">
      <Label htmlFor={id}>{t('dishes.searchName')}</Label>
      <Input id={id} type="search" value={value} onChange={(e) => onChange(e.target.value)} />
    </div>
  );
}

const nameMatches = (name: string, q: string) =>
  name.toLocaleLowerCase('vi').includes(q.trim().toLocaleLowerCase('vi'));

function MainDishes({ canteen }: { canteen: { id: string; name: string } }) {
  const qc = useQueryClient();
  const [showArchived, setShowArchived] = useState(false);
  const [filter, setFilter] = useState('');
  const [q, setQ] = useState('');
  const [editing, setEditing] = useState<Editing | null>(null);
  const list = useLibrary<DishView>('/admin/dishes', canteen.id, showArchived);
  const types = useDishTypes();
  const activeTypes = (types.data?.items ?? []).filter((x) => x.active);

  const save = useMutation({
    mutationFn: (e: Editing) => {
      const body = { name: e.name, typeId: e.typeId, sessions: ['LUNCH'] };
      return e.id
        ? api.put(`/admin/dishes/${e.id}`, body)
        : api.post('/admin/dishes', { ...body, canteenId: canteen.id });
    },
    onSuccess: () => {
      setEditing(null);
      toast.success(t('common.saved'));
      void qc.invalidateQueries({ queryKey: ['/admin/dishes'] });
      void qc.invalidateQueries({ queryKey: ['/admin/dish-types'] });
    },
  });
  const archive = useMutation({
    mutationFn: ({ id, archived }: { id: string; archived: boolean }) =>
      api.post(`/admin/dishes/${id}/${archived ? 'archive' : 'restore'}`),
    onSuccess: (_res, v) => {
      // Archiving is reversible: the toast offers the way back.
      toast.success(t(v.archived ? 'dishes.archivedToast' : 'dishes.restoredToast'), {
        action: v.archived
          ? { label: t('common.undo'), onClick: () => archive.mutate({ ...v, archived: false }) }
          : undefined,
      });
      void qc.invalidateQueries({ queryKey: ['/admin/dishes'] });
    },
  });

  const items = (list.data?.items ?? []).filter(
    (d) => (!filter || d.typeId === filter) && nameMatches(d.name, q),
  );
  const { pageItems, pagination } = usePagination(items, `${filter}|${q}|${showArchived}`);
  const error = archive.error ?? list.error ?? types.error;
  const editingType = (types.data?.items ?? []).find((x) => x.id === editing?.typeId);

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end gap-3">
        <NameSearch id="dish-q" value={q} onChange={setQ} />
        <div className="flex w-full flex-col gap-2 sm:w-48">
          <Label htmlFor="dish-filter">{t('dishes.filterType')}</Label>
          <ResponsiveSelect
            id="dish-filter"
            value={filter}
            onValueChange={setFilter}
            options={[
              { value: '', label: t('common.all') },
              ...(types.data?.items ?? []).map((type) => ({ value: type.id, label: type.name })),
            ]}
          />
        </div>
        <label className="flex min-h-11 items-center gap-2 text-sm">
          <Checkbox checked={showArchived} onCheckedChange={(v) => setShowArchived(v === true)} />
          {t('dishes.showArchived')}
        </label>
        <div className="ml-auto flex flex-wrap gap-2">
          <LibraryImport canteen={canteen} />
          <Button
            disabled={activeTypes.length === 0}
            onClick={() => {
              save.reset();
              setEditing({ id: null, name: '', typeId: activeTypes[0]?.id ?? '' });
            }}
          >
            {t('dishes.addMain')}
          </Button>
        </div>
      </div>

      <AppDialog
        open={editing !== null}
        onClose={() => setEditing(null)}
        title={editing?.id ? t('dishes.editMain') : t('dishes.addMain')}
      >
        {editing ? (
          <form
            className="flex flex-col gap-4"
            onSubmit={(e) => {
              e.preventDefault();
              // One request per click, even with a double click or Enter pressed twice.
              if (!save.isPending) save.mutate(editing);
            }}
          >
            <div className="flex flex-col gap-2">
              <Label htmlFor="dish-name">{t('common.name')}</Label>
              <Input
                id="dish-name"
                required
                maxLength={120}
                placeholder={t('dishes.namePlaceholder')}
                value={editing.name}
                onChange={(e) => setEditing({ ...editing, name: e.target.value })}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="dish-type">{t('dishes.type')}</Label>
              <ResponsiveSelect
                id="dish-type"
                value={editing.typeId}
                onValueChange={(v) => setEditing({ ...editing, typeId: v })}
                options={(types.data?.items ?? [])
                  .filter((x) => x.active || x.id === editing.typeId)
                  .map((type) => ({ value: type.id, label: type.name }))}
              />
            </div>
            <p className="text-sm text-muted-foreground">
              {t('dishes.displayName')}:{' '}
              {editing.name.trim() && editingType ? (
                <DishName
                  type={editingType}
                  name={editing.name.trim().replace(/\s+/g, ' ')}
                  className="text-foreground"
                />
              ) : (
                '…'
              )}
            </p>
            {save.error ? <Alert variant="destructive">{saveErrorText(save.error)}</Alert> : null}
            <DialogActions
              pending={save.isPending}
              disabled={!editing.name.trim() || !editing.typeId}
              onCancel={() => setEditing(null)}
            />
          </form>
        ) : null}
      </AppDialog>

      {error ? <Alert variant="destructive">{apiErrorText(error)}</Alert> : null}

      {list.isLoading ? <LoadingRows /> : null}
      <Table containerClassName={list.isLoading ? 'hidden' : undefined} className="min-w-[30rem]">
        <TableHeader>
          <TableRow>
            <TableHead>{t('dishes.displayName')}</TableHead>
            <TableHead>{t('dishes.type')}</TableHead>
            <TableHead>{t('dishes.sessions')}</TableHead>
            <TableHead className="text-right">{t('common.actions')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {pageItems.map((d) => (
            <TableRow key={d.id} className={d.archivedAt ? 'text-muted-foreground' : undefined}>
              <TableCell>
                <DishName type={d.type} name={d.name} />{' '}
                {d.archivedAt ? <Badge variant="outline">{t('dishes.archived')}</Badge> : null}
              </TableCell>
              <TableCell>{d.type.name}</TableCell>
              <TableCell>{d.sessions.map((s) => vi.sessions[s]).join(', ')}</TableCell>
              <TableCell className="text-right">
                <div className="flex justify-end gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => {
                      save.reset();
                      setEditing({ id: d.id, name: d.name, typeId: d.typeId });
                    }}
                  >
                    {t('common.edit')}
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={archive.isPending}
                    onClick={() => archive.mutate({ id: d.id, archived: !d.archivedAt })}
                  >
                    {d.archivedAt ? t('common.restore') : t('common.archive')}
                  </Button>
                </div>
              </TableCell>
            </TableRow>
          ))}
          {items.length === 0 && !list.isLoading ? (
            <TableRow>
              <TableCell colSpan={4} className="text-muted-foreground">
                {t('dishes.empty')}
              </TableCell>
            </TableRow>
          ) : null}
        </TableBody>
      </Table>
      <Pagination {...pagination} />
    </div>
  );
}

function Desserts({ canteen }: { canteen: { id: string; name: string } }) {
  const qc = useQueryClient();
  const [showArchived, setShowArchived] = useState(false);
  const [q, setQ] = useState('');
  const [editing, setEditing] = useState<{ id: string | null; name: string } | null>(null);
  const list = useLibrary<DessertView>('/admin/desserts', canteen.id, showArchived);

  const save = useMutation({
    mutationFn: (e: { id: string | null; name: string }) =>
      e.id
        ? api.put(`/admin/desserts/${e.id}`, { name: e.name })
        : api.post('/admin/desserts', { canteenId: canteen.id, name: e.name }),
    onSuccess: () => {
      setEditing(null);
      toast.success(t('common.saved'));
      void qc.invalidateQueries({ queryKey: ['/admin/desserts'] });
    },
  });
  const archive = useMutation({
    mutationFn: ({ id, archived }: { id: string; archived: boolean }) =>
      api.post(`/admin/desserts/${id}/${archived ? 'archive' : 'restore'}`),
    onSuccess: (_res, v) => {
      // Archiving is reversible: the toast offers the way back.
      toast.success(t(v.archived ? 'dishes.archivedToast' : 'dishes.restoredToast'), {
        action: v.archived
          ? { label: t('common.undo'), onClick: () => archive.mutate({ ...v, archived: false }) }
          : undefined,
      });
      void qc.invalidateQueries({ queryKey: ['/admin/desserts'] });
    },
  });
  const error = archive.error ?? list.error;
  const items = (list.data?.items ?? []).filter((d) => nameMatches(d.name, q));
  const { pageItems, pagination } = usePagination(items, `${q}|${showArchived}`);

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end gap-3">
        <NameSearch id="dessert-q" value={q} onChange={setQ} />
        <label className="flex min-h-11 items-center gap-2 text-sm">
          <Checkbox checked={showArchived} onCheckedChange={(v) => setShowArchived(v === true)} />
          {t('dishes.showArchived')}
        </label>
        <div className="ml-auto flex flex-wrap gap-2">
          <LibraryImport canteen={canteen} />
          <Button
            onClick={() => {
              save.reset();
              setEditing({ id: null, name: '' });
            }}
          >
            {t('dishes.addDessert')}
          </Button>
        </div>
      </div>
      <AppDialog
        open={editing !== null}
        onClose={() => setEditing(null)}
        title={editing?.id ? t('dishes.editDessert') : t('dishes.addDessert')}
      >
        {editing ? (
          <form
            className="flex flex-col gap-4"
            onSubmit={(e) => {
              e.preventDefault();
              if (!save.isPending) save.mutate(editing);
            }}
          >
            <div className="flex flex-col gap-2">
              <Label htmlFor="dessert-name">{t('common.name')}</Label>
              <Input
                id="dessert-name"
                required
                maxLength={120}
                value={editing.name}
                onChange={(e) => setEditing({ ...editing, name: e.target.value })}
              />
            </div>
            {save.error ? <Alert variant="destructive">{saveErrorText(save.error)}</Alert> : null}
            <DialogActions
              pending={save.isPending}
              disabled={!editing.name.trim()}
              onCancel={() => setEditing(null)}
            />
          </form>
        ) : null}
      </AppDialog>
      {error ? <Alert variant="destructive">{apiErrorText(error)}</Alert> : null}
      {list.isLoading ? <LoadingRows /> : null}
      <Table containerClassName={list.isLoading ? 'hidden' : undefined}>
        <TableHeader>
          <TableRow>
            <TableHead>{t('common.name')}</TableHead>
            <TableHead className="text-right">{t('common.actions')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {pageItems.map((d) => (
            <TableRow key={d.id} className={d.archivedAt ? 'text-muted-foreground' : undefined}>
              <TableCell>
                {d.name}{' '}
                {d.archivedAt ? <Badge variant="outline">{t('dishes.archived')}</Badge> : null}
              </TableCell>
              <TableCell className="text-right">
                <div className="flex justify-end gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => {
                      save.reset();
                      setEditing({ id: d.id, name: d.name });
                    }}
                  >
                    {t('common.edit')}
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={archive.isPending}
                    onClick={() => archive.mutate({ id: d.id, archived: !d.archivedAt })}
                  >
                    {d.archivedAt ? t('common.restore') : t('common.archive')}
                  </Button>
                </div>
              </TableCell>
            </TableRow>
          ))}
          {items.length === 0 && !list.isLoading ? (
            <TableRow>
              <TableCell colSpan={2} className="text-muted-foreground">
                {t('dishes.empty')}
              </TableCell>
            </TableRow>
          ) : null}
        </TableBody>
      </Table>
      <Pagination {...pagination} />
    </div>
  );
}

/** Colours offered first; any other #rrggbb can be typed or picked. */
const SWATCHES = ['#b91c1c', '#15803d', '#b45309', '#0369a1', '#7c3aed', '#be185d', '#0f766e'];

type TypeDraft = {
  id: string | null;
  code: string;
  name: string;
  color: string;
  sortOrder: string;
  minPerDay: string;
  maxPerDay: string;
  active: boolean;
};

const toNumber = (v: string) => (v.trim() === '' ? null : Number(v));

function perDayText(type: DishTypeView): string {
  const { minPerDay: min, maxPerDay: max } = type;
  if (min === null && max === null) return t('dishes.types.anyCount');
  if (min !== null && max !== null) {
    return min === max ? String(min) : t('dishes.types.range', { min, max });
  }
  return min !== null
    ? t('dishes.types.atLeast', { min })
    : t('dishes.types.atMost', { max: max! });
}

/** BR-10 / BR-13: dish types (Mặn, Chay, Cháo, Eat clean, ...) with colour and usual bounds. */
function DishTypes() {
  const qc = useQueryClient();
  const list = useDishTypes();
  const [editing, setEditing] = useState<TypeDraft | null>(null);
  const save = useMutation({
    mutationFn: (d: TypeDraft) => {
      const body = {
        name: d.name,
        color: d.color,
        sortOrder: Number(d.sortOrder) || 0,
        minPerDay: toNumber(d.minPerDay),
        maxPerDay: toNumber(d.maxPerDay),
      };
      return d.id
        ? api.patch(`/admin/dish-types/${d.id}`, { ...body, active: d.active })
        : api.post('/admin/dish-types', { ...body, code: d.code });
    },
    onSuccess: () => {
      setEditing(null);
      toast.success(t('common.saved'));
      void qc.invalidateQueries({ queryKey: ['/admin/dish-types'] });
      void qc.invalidateQueries({ queryKey: ['/admin/dishes'] });
    },
  });
  const items = list.data?.items ?? [];
  const validColor = /^#[0-9a-fA-F]{6}$/.test(editing?.color ?? '');
  const badNumber = (v: string) =>
    v.trim() !== '' && (!Number.isInteger(Number(v)) || Number(v) < 0 || Number(v) > 20);

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <p className="max-w-prose text-sm text-muted-foreground">{t('dishes.types.intro')}</p>
        <Button
          className="ml-auto"
          onClick={() => {
            save.reset();
            setEditing({
              id: null,
              code: '',
              name: '',
              color: SWATCHES[items.length % SWATCHES.length]!,
              sortOrder: String(items.length + 1),
              minPerDay: '',
              maxPerDay: '',
              active: true,
            });
          }}
        >
          {t('dishes.types.add')}
        </Button>
      </div>

      <AppDialog
        open={editing !== null}
        onClose={() => setEditing(null)}
        title={editing?.id ? t('dishes.types.edit') : t('dishes.types.add')}
      >
        {editing ? (
          <form
            className="flex flex-col gap-4"
            onSubmit={(e) => {
              e.preventDefault();
              if (!save.isPending) save.mutate(editing);
            }}
          >
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-2">
                <Label htmlFor="type-code">{t('common.code')}</Label>
                <Input
                  id="type-code"
                  required
                  maxLength={32}
                  disabled={editing.id !== null}
                  placeholder="EATCLEAN"
                  value={editing.code}
                  onChange={(e) => setEditing({ ...editing, code: e.target.value.toUpperCase() })}
                />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="type-name">{t('common.name')}</Label>
                <Input
                  id="type-name"
                  required
                  maxLength={40}
                  placeholder="Eat clean"
                  value={editing.name}
                  onChange={(e) => setEditing({ ...editing, name: e.target.value })}
                />
              </div>
            </div>
            <fieldset className="flex flex-col gap-2">
              <legend className="mb-2 text-sm font-medium">{t('dishes.types.color')}</legend>
              <div className="flex flex-wrap items-center gap-2">
                {SWATCHES.map((c) => (
                  <button
                    key={c}
                    type="button"
                    aria-label={c}
                    aria-pressed={editing.color === c}
                    className="h-8 w-8 rounded-full ring-offset-2 ring-offset-background aria-pressed:ring-2 aria-pressed:ring-foreground"
                    style={{ backgroundColor: c }}
                    onClick={() => setEditing({ ...editing, color: c })}
                  />
                ))}
                <Input
                  aria-label={t('dishes.types.color')}
                  type="color"
                  className="h-10 w-14 py-0.5 px-2"
                  value={validColor ? editing.color : '#000000'}
                  onChange={(e) => setEditing({ ...editing, color: e.target.value })}
                />
              </div>
              <p className="text-sm">
                {t('dishes.types.preview')}:{' '}
                {validColor ? (
                  <DishName
                    type={{ id: '', name: editing.name || '…', color: editing.color }}
                    name={t('dishes.types.previewDish')}
                  />
                ) : null}
              </p>
            </fieldset>
            <div className="grid gap-4 sm:grid-cols-3">
              <div className="flex flex-col gap-2">
                <Label htmlFor="type-order">{t('dishes.types.sortOrder')}</Label>
                <Input
                  id="type-order"
                  inputMode="numeric"
                  value={editing.sortOrder}
                  onChange={(e) => setEditing({ ...editing, sortOrder: e.target.value })}
                />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="type-min">
                  {t('dishes.types.perDay')}: {t('dishes.types.minPerDay')}
                </Label>
                <Input
                  id="type-min"
                  inputMode="numeric"
                  placeholder={t('dishes.types.anyCount')}
                  aria-invalid={badNumber(editing.minPerDay)}
                  value={editing.minPerDay}
                  onChange={(e) => setEditing({ ...editing, minPerDay: e.target.value })}
                />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="type-max">
                  {t('dishes.types.perDay')}: {t('dishes.types.maxPerDay')}
                </Label>
                <Input
                  id="type-max"
                  inputMode="numeric"
                  placeholder={t('dishes.types.anyCount')}
                  aria-invalid={badNumber(editing.maxPerDay)}
                  value={editing.maxPerDay}
                  onChange={(e) => setEditing({ ...editing, maxPerDay: e.target.value })}
                />
              </div>
            </div>
            {editing.id ? (
              <label className="flex min-h-11 items-center gap-2 text-sm">
                <Checkbox
                  checked={editing.active}
                  onCheckedChange={(v) => setEditing({ ...editing, active: v === true })}
                />
                {t('common.active')}
                <span className="text-muted-foreground">· {t('dishes.types.inactiveHint')}</span>
              </label>
            ) : null}
            {save.error ? (
              <Alert variant="destructive">
                {save.error instanceof ApiError && save.error.code === 'CONFLICT'
                  ? t('dishes.types.codeTaken')
                  : apiErrorText(save.error)}
              </Alert>
            ) : null}
            <DialogActions
              pending={save.isPending}
              disabled={
                !editing.name.trim() ||
                !editing.code.trim() ||
                !validColor ||
                badNumber(editing.minPerDay) ||
                badNumber(editing.maxPerDay)
              }
              onCancel={() => setEditing(null)}
            />
          </form>
        ) : null}
      </AppDialog>

      {list.error ? <Alert variant="destructive">{apiErrorText(list.error)}</Alert> : null}
      {list.isLoading ? <LoadingRows /> : null}
      <Table containerClassName={list.isLoading ? 'hidden' : undefined} className="min-w-[34rem]">
        <TableHeader>
          <TableRow>
            <TableHead>{t('common.name')}</TableHead>
            <TableHead>{t('common.code')}</TableHead>
            <TableHead>{t('dishes.types.perDay')}</TableHead>
            <TableHead>{t('dishes.types.dishCount')}</TableHead>
            <TableHead className="text-right">{t('common.actions')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.map((type) => (
            <TableRow key={type.id} className={type.active ? undefined : 'text-muted-foreground'}>
              <TableCell>
                <DishTypeTag type={type} />{' '}
                {!type.active ? <Badge variant="outline">{t('common.inactive')}</Badge> : null}
              </TableCell>
              <TableCell className="font-mono text-xs">{type.code}</TableCell>
              <TableCell>{perDayText(type)}</TableCell>
              <TableCell className="tabular-nums">{type.dishCount}</TableCell>
              <TableCell className="text-right">
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    save.reset();
                    setEditing({
                      id: type.id,
                      code: type.code,
                      name: type.name,
                      color: type.color,
                      sortOrder: String(type.sortOrder),
                      minPerDay: type.minPerDay === null ? '' : String(type.minPerDay),
                      maxPerDay: type.maxPerDay === null ? '' : String(type.maxPerDay),
                      active: type.active,
                    });
                  }}
                >
                  {t('common.edit')}
                </Button>
              </TableCell>
            </TableRow>
          ))}
          {items.length === 0 && !list.isLoading ? (
            <TableRow>
              <TableCell colSpan={5} className="text-muted-foreground">
                {t('dishes.types.empty')}
              </TableCell>
            </TableRow>
          ) : null}
        </TableBody>
      </Table>
    </div>
  );
}

function DialogActions({
  pending,
  disabled,
  onCancel,
}: {
  pending: boolean;
  disabled: boolean;
  onCancel: () => void;
}) {
  return (
    <div className="flex flex-col-reverse gap-2 pt-2 sm:flex-row sm:justify-end">
      <Button type="button" variant="outline" onClick={onCancel}>
        {t('common.cancel')}
      </Button>
      <Button type="submit" disabled={pending || disabled}>
        {pending ? t('common.saving') : t('common.save')}
      </Button>
    </div>
  );
}
