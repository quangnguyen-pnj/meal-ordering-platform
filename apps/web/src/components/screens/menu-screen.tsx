'use client';

import {
  closestCenter,
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  TouchSensor,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
  pointerWithin,
  type CollisionDetection,
  type DragEndEvent,
  type DragOverEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import { checkMenuMix, isoWeekdayOf } from '@meal/shared';
import type {
  DessertView,
  DishTypeLabel,
  DishTypeView,
  DishView,
  MenuSaveResult,
  MenuWeekView,
} from '@/lib/api-types';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowDown,
  ArrowLeftRight,
  ArrowUp,
  Check,
  CirclePower,
  GripVertical,
  Plus,
  X,
} from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { toast } from 'sonner';
import { useStore } from 'zustand';
import { ResponsiveSelect } from '@/components/responsive-select';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { AppDialog } from '@/components/app-dialog';
import { useConfirm } from '@/components/confirm-dialog';
import { DishName } from '@/components/dish-name';
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/components/ui/command';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { LoadingRows } from '@/components/ui/skeleton';
import { WeekNav } from '@/components/week-nav';
import { api } from '@/lib/api';
import { BANNERS_KEY } from '@/lib/banners';
import { apiErrorText } from '@/lib/errors';
import {
  createMenuBuilderStore,
  draftGaps,
  type DraftDay,
  type MenuBuilderStore,
} from '@/lib/menu-builder-store';
import { cn, formatCloseAt, formatDate } from '@/lib/utils';
import { t, vi } from '@/messages';

/**
 * A dish dropped on another dish replaces it; dropped anywhere else on a day it is added. With
 * the pointer, the dish under it wins over its day; keyboard drags fall back to the nearest one.
 */
const dropTarget: CollisionDetection = (args) => {
  const hits = pointerWithin(args);
  if (hits.length > 0) {
    const item = hits.find((h) => String(h.id).startsWith('item:'));
    return item ? [item] : hits;
  }
  return closestCenter(args);
};

export type Drop =
  | { kind: 'addDish' | 'addDessert'; date: string; id: string }
  | { kind: 'replace'; date: string; index: number; id: string };

/**
 * What a drop means. Targets are "day:<date>" (add) and "item:<date>:<index>" (replace that
 * dish, BR-14 once published); a dessert always joins the day, whatever it lands on.
 */
export function resolveDrop(active: string, over: string | null): Drop | null {
  if (!over) return null;
  const [scope, date, index] = over.split(':');
  const [kind, id] = active.split(':');
  if (!date || !id || (scope !== 'day' && scope !== 'item')) return null;
  if (kind === 'dessert') return { kind: 'addDessert', date, id };
  if (kind !== 'dish') return null;
  return scope === 'item'
    ? { kind: 'replace', date, index: Number(index), id }
    : { kind: 'addDish', date, id };
}

interface CanteensResponse {
  items: Array<{ id: string; code: string; name: string }>;
  upcomingWeekStart: string;
}

type DishLabel = { type: DishTypeLabel; displayName: string };

/**
 * /admin/menu: canteen picker, the canteen's dish library, week grid, optional draft save and
 * publish straight away (BR-08..BR-15). Publishing needs a main dish and a dessert on every
 * serving day (2026-10-23).
 */
export function MenuScreen() {
  const canteens = useQuery({
    queryKey: ['menu-canteens'],
    queryFn: () => api.get<CanteensResponse>('/admin/menu/canteens'),
  });
  const [canteenId, setCanteenId] = useState<string | null>(null);
  const [weekStart, setWeekStart] = useState<string | null>(null);

  useEffect(() => {
    if (canteens.data && !canteenId) setCanteenId(canteens.data.items[0]?.id ?? null);
    if (canteens.data && !weekStart) setWeekStart(canteens.data.upcomingWeekStart);
  }, [canteens.data, canteenId, weekStart]);

  if (canteens.error) return <Alert variant="destructive">{apiErrorText(canteens.error)}</Alert>;
  if (!canteens.data) return <LoadingRows rows={6} />;
  if (canteens.data.items.length === 0) return <Alert>{t('menu.noCanteen')}</Alert>;

  return (
    <Card>
      <CardHeader className="gap-4">
        <CardTitle>{t('menu.title')}</CardTitle>
        <div className="flex flex-wrap items-end gap-4">
          <div className="flex w-full flex-col gap-2 sm:w-60">
            <Label htmlFor="menu-canteen">{t('menu.canteen')}</Label>
            <ResponsiveSelect
              id="menu-canteen"
              value={canteenId ?? ''}
              onValueChange={setCanteenId}
              options={canteens.data.items.map((c) => ({ value: c.id, label: c.name }))}
            />
          </div>
          <div className="flex flex-col gap-2">
            <p className="text-sm font-medium leading-none">{t('menu.week')}</p>
            {weekStart ? (
              <WeekNav
                weekStart={weekStart}
                onChange={setWeekStart}
                homeWeekStart={canteens.data.upcomingWeekStart}
              />
            ) : null}
          </div>
        </div>
      </CardHeader>
      <CardContent>
        {canteenId && weekStart ? (
          <WeekEditor
            key={`${canteenId}:${weekStart}`}
            canteenId={canteenId}
            weekStart={weekStart}
          />
        ) : null}
      </CardContent>
    </Card>
  );
}

function WeekEditor({ canteenId, weekStart }: { canteenId: string; weekStart: string }) {
  const qc = useQueryClient();
  const [store] = useState<MenuBuilderStore>(() => createMenuBuilderStore());
  const days = useStore(store, (s) => s.days);
  const dirty = useStore(store, (s) => s.dirty);
  const [pickerDate, setPickerDate] = useState<string | null>(null);
  // The library entry being dragged ("dish:<id>" / "dessert:<id>"), drawn in a DragOverlay.
  const [dragging, setDragging] = useState<string | null>(null);
  // Where it would land, so the moving copy can say "add to …" or "replace …".
  const [overId, setOverId] = useState<string | null>(null);
  const [confirm, confirmElement] = useConfirm();
  const [reason, setReason] = useState('');

  const weekKey = ['menu-week', canteenId, weekStart];
  const week = useQuery({
    queryKey: weekKey,
    queryFn: () =>
      api.get<MenuWeekView>(`/admin/menu/weeks?canteenId=${canteenId}&weekStart=${weekStart}`),
  });
  // Each canteen has its own library (2026-10-23).
  const dishes = useQuery({
    queryKey: ['/admin/dishes', canteenId, false],
    queryFn: () =>
      api.get<{ items: DishView[] }>(`/admin/dishes?canteenId=${canteenId}&includeArchived=false`),
  });
  const desserts = useQuery({
    queryKey: ['/admin/desserts', canteenId, false],
    queryFn: () =>
      api.get<{ items: DessertView[] }>(
        `/admin/desserts?canteenId=${canteenId}&includeArchived=false`,
      ),
  });
  const dishTypes = useQuery({
    queryKey: ['/admin/dish-types'],
    queryFn: () => api.get<{ items: DishTypeView[] }>('/admin/dish-types'),
  });
  // BR-13: each active type's usual bounds, for the live mix note.
  const mixRules = useMemo(
    () =>
      (dishTypes.data?.items ?? [])
        .filter((x) => x.active)
        .map((x) => ({
          typeId: x.id,
          typeName: x.name,
          minPerDay: x.minPerDay,
          maxPerDay: x.maxPerDay,
        })),
    [dishTypes.data],
  );

  useEffect(() => {
    if (week.data) store.getState().load(week.data);
  }, [week.data, store]);

  // Names for every dish shown, including archived ones still on the menu.
  const dishById = useMemo(() => {
    const map = new Map<string, DishLabel>();
    for (const d of week.data?.days ?? [])
      for (const i of d.items) map.set(i.dishId, { type: i.type, displayName: i.displayName });
    for (const d of dishes.data?.items ?? []) map.set(d.id, d);
    return map;
  }, [week.data, dishes.data]);
  const dessertName = useMemo(() => {
    const map = new Map<string, string>();
    for (const d of week.data?.days ?? []) for (const x of d.desserts) map.set(x.id, x.name);
    for (const d of desserts.data?.items ?? []) map.set(d.id, d.name);
    return map;
  }, [week.data, desserts.data]);

  const view = week.data;
  const published = view !== undefined && view.status !== 'NEW' && view.status !== 'DRAFT';
  const locked = new Set(view?.lockedDates ?? []);

  const save = useMutation({
    mutationFn: () =>
      api.put<MenuSaveResult>('/admin/menu/weeks', {
        canteenId,
        weekStart,
        reason: reason.trim() || undefined,
        days: store.getState().days.filter((d) => !locked.has(d.date)),
      }),
    onSuccess: (res) => {
      qc.setQueryData(weekKey, res.menu);
      store.getState().load(res.menu);
      setReason('');
      // The admin may have a meal on a changed dish too (BR-14).
      void qc.invalidateQueries({ queryKey: BANNERS_KEY });
      toast.success(
        published
          ? t('menu.effects', {
              moved: res.effects.movedRegistrations,
              flagged: res.effects.flaggedRegistrations,
              notified: res.effects.notifiedEmployees,
            })
          : t('common.saved'),
      );
    },
  });
  // No draft save needed first: the days on screen are saved and published together.
  const publish = useMutation({
    mutationFn: () =>
      api.post<MenuWeekView>('/admin/menu/weeks/publish', {
        canteenId,
        weekStart,
        days: store.getState().days.filter((d) => !locked.has(d.date)),
      }),
    onSuccess: (res) => {
      qc.setQueryData(weekKey, res);
      store.getState().load(res);
      // The "menu not published" banner (BR-06) goes away now, not on the next reload.
      void qc.invalidateQueries({ queryKey: BANNERS_KEY });
      toast.success(t('menu.published'));
    },
  });
  const gaps = draftGaps(days);

  function onDragStart(event: DragStartEvent) {
    setDragging(String(event.active.id));
  }

  function onDragEnd(event: DragEndEvent) {
    setDragging(null);
    setOverId(null);
    const drop = resolveDrop(String(event.active.id), event.over ? String(event.over.id) : null);
    if (!drop || locked.has(drop.date)) return;
    const actions = store.getState();
    if (drop.kind === 'addDessert') actions.addDessert(drop.date, drop.id);
    else if (drop.kind === 'replace') actions.replaceItem(drop.date, drop.index, drop.id);
    else actions.addDish(drop.date, drop.id);
  }

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(TouchSensor, { activationConstraint: { delay: 150, tolerance: 6 } }),
    useSensor(KeyboardSensor),
  );

  // Shown on the moving copy: the target itself is hidden under it.
  function dropHint(active: string, over: string | null): string | null {
    const drop = resolveDrop(active, over);
    if (!drop || locked.has(drop.date)) return null;
    const weekday = vi.weekdays[isoWeekdayOf(drop.date) as keyof typeof vi.weekdays];
    if (drop.kind !== 'replace') return t('menu.dropToAdd', { day: weekday });
    const current = days.find((d) => d.date === drop.date)?.items[drop.index];
    return t('menu.dropToReplaceDish', {
      dish: current ? (dishById.get(current.dishId)?.displayName ?? '') : '',
    });
  }

  async function askPublish() {
    if (!view) return;
    const ok = await confirm({
      title: t('menu.publishTitle'),
      description: (
        <>
          <span className="block">{t('menu.publishConfirm')}</span>
          {dirty ? <span className="mt-2 block">{t('menu.publishWithChanges')}</span> : null}
          <strong className="mt-2 block text-foreground">
            {t('menu.closeAt', { time: formatCloseAt(view.closeAt) })}
          </strong>
        </>
      ),
      confirmLabel: t('menu.publish'),
    });
    if (ok && !publish.isPending) publish.mutate();
  }

  if (week.error) return <Alert variant="destructive">{apiErrorText(week.error)}</Alert>;
  if (!view) return <LoadingRows rows={6} />;

  const error = save.error ?? publish.error;
  const statusVariant = published ? 'success' : view.status === 'DRAFT' ? 'warning' : 'outline';

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={dropTarget}
      onDragStart={onDragStart}
      onDragOver={(e: DragOverEvent) => setOverId(e.over ? String(e.over.id) : null)}
      onDragEnd={onDragEnd}
      onDragCancel={() => {
        setDragging(null);
        setOverId(null);
      }}
    >
      <div className="flex flex-col gap-5">
        <div className="flex flex-wrap items-center gap-4">
          <Badge variant={statusVariant}>{vi.menu.status[view.status]}</Badge>
          <span className="text-sm">
            {view.closeAtIsExpected
              ? t('menu.expectedCloseAt', { time: formatCloseAt(view.closeAt) })
              : t('menu.closeAt', { time: formatCloseAt(view.closeAt) })}
          </span>
          {view.latePublish ? <Badge variant="warning">{t('menu.latePublish')}</Badge> : null}
        </div>
        {published ? <Alert>{t('menu.publishedEditHint')}</Alert> : null}

        <div className="grid gap-5 lg:grid-cols-[18rem_1fr]">
          <Library dishes={dishes.data?.items ?? []} desserts={desserts.data?.items ?? []} />
          <div className="grid gap-4 sm:grid-cols-2 2xl:grid-cols-3">
            {days.map((day) => (
              <DayColumn
                key={day.date}
                day={day}
                store={store}
                published={published}
                wasServing={view.days.find((d) => d.date === day.date)?.isServing ?? false}
                locked={locked.has(day.date)}
                onAdd={() => setPickerDate(day.date)}
                draggingDish={dragging?.startsWith('dish:') ?? false}
                dishById={dishById}
                dessertName={dessertName}
                replacementOptions={dishes.data?.items ?? []}
                mixRules={mixRules}
              />
            ))}
          </div>
        </div>

        <DishPicker
          date={pickerDate}
          day={days.find((d) => d.date === pickerDate)}
          dishes={dishes.data?.items ?? []}
          desserts={desserts.data?.items ?? []}
          onClose={() => setPickerDate(null)}
          onAddDish={(id) => {
            if (pickerDate) store.getState().addDish(pickerDate, id);
          }}
          onAddDessert={(id) => {
            if (pickerDate) store.getState().addDessert(pickerDate, id);
          }}
        />
        {confirmElement}
        {/* Drawn over the whole page so a dish stays visible outside the scrolling library. */}
        {typeof document !== 'undefined'
          ? createPortal(
              <DragOverlay dropAnimation={null}>
                {dragging ? (
                  <DragPreview
                    hint={dropHint(dragging, overId)}
                    label={
                      dragging.startsWith('dish:')
                        ? dishById.get(dragging.slice(5))
                        : (dessertName.get(dragging.slice(8)) ?? '')
                    }
                  />
                ) : null}
              </DragOverlay>,
              document.body,
            )
          : null}

        {error ? <Alert variant="destructive">{apiErrorText(error)}</Alert> : null}
        {dirty ? <p className="text-sm text-muted-foreground">{t('menu.unsaved')}</p> : null}

        <div className="flex flex-wrap items-end gap-4">
          {published ? (
            <div className="flex min-w-72 flex-1 flex-col gap-2">
              <Label htmlFor="menu-reason">{t('common.reasonOptional')}</Label>
              <Input
                id="menu-reason"
                maxLength={500}
                placeholder={t('menu.reasonPlaceholder')}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
            </div>
          ) : null}
          <Button
            variant={published ? 'default' : 'outline'}
            disabled={save.isPending || publish.isPending || (!dirty && view.id !== null)}
            onClick={() => save.mutate()}
          >
            {save.isPending
              ? t('common.saving')
              : published
                ? t('menu.saveChanges')
                : t('menu.saveDraft')}
          </Button>
          {!published ? (
            <Button
              disabled={publish.isPending || save.isPending || gaps.length > 0}
              aria-describedby={gaps.length > 0 ? 'publish-hint' : undefined}
              onClick={askPublish}
            >
              {publish.isPending ? t('menu.publishing') : t('menu.publish')}
            </Button>
          ) : null}
          {!published && gaps.length > 0 ? (
            <div id="publish-hint" className="basis-full text-sm text-muted-foreground">
              <p>{t('menu.publishBlocked')}</p>
              <ul className="mt-1 list-disc pl-5">
                {gaps.map((g) => (
                  <li key={g.date}>
                    {t('menu.gap', {
                      day: `${vi.weekdays[isoWeekdayOf(g.date) as keyof typeof vi.weekdays]} ${formatDate(g.date)}`,
                      what: g.gaps.map((x) => vi.menu.gaps[x]).join(', '),
                    })}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </div>
      </div>
    </DndContext>
  );
}

/** Desktop drag source; phones and keyboards use the per-day "Thêm món" picker instead. */
function Library({ dishes, desserts }: { dishes: DishView[]; desserts: DessertView[] }) {
  const [q, setQ] = useState('');
  const match = (name: string) => name.toLowerCase().includes(q.trim().toLowerCase());
  return (
    <aside className="hidden flex-col gap-4 rounded-lg bg-muted/40 p-5 lg:sticky lg:top-4 lg:flex lg:max-h-[80vh] lg:self-start lg:overflow-y-auto">
      <p className="font-medium">{t('menu.library')}</p>
      <p className="text-xs text-muted-foreground">{t('menu.libraryHint')}</p>
      <Input
        type="search"
        aria-label={t('common.search')}
        placeholder={t('common.search')}
        value={q}
        onChange={(e) => setQ(e.target.value)}
      />
      <p className="text-xs font-semibold uppercase text-muted-foreground">
        {t('menu.mainDishes')}
      </p>
      <ul className="flex flex-col gap-1">
        {dishes
          .filter((d) => match(d.displayName))
          .map((d) => (
            <LibraryEntry
              key={d.id}
              dragId={`dish:${d.id}`}
              label={d.displayName}
              content={<DishName type={d.type} name={d.name} />}
            />
          ))}
      </ul>
      <p className="text-xs font-semibold uppercase text-muted-foreground">{t('menu.desserts')}</p>
      <ul className="flex flex-col gap-1">
        {desserts
          .filter((d) => match(d.name))
          .map((d) => (
            <LibraryEntry key={d.id} dragId={`dessert:${d.id}`} label={d.name} />
          ))}
      </ul>
      {dishes.length === 0 && desserts.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t('menu.libraryEmpty')}</p>
      ) : null}
    </aside>
  );
}

function LibraryEntry({
  dragId,
  label,
  content,
}: {
  dragId: string;
  label: string;
  content?: React.ReactNode;
}) {
  // The entry stays put (dimmed); the moving copy is the DragOverlay in WeekEditor.
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: dragId });
  return (
    <li
      ref={setNodeRef}
      className={cn(
        'flex cursor-grab touch-none items-center gap-2 rounded-md bg-card px-2 py-2 text-sm shadow-block transition-shadow hover:shadow-raised',
        isDragging && 'opacity-40',
      )}
      {...attributes}
      {...listeners}
      aria-label={label}
    >
      <GripVertical className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
      <span className="flex-1">{content ?? label}</span>
    </li>
  );
}

function DragPreview({
  label,
  hint,
}: {
  label: DishLabel | string | undefined;
  hint: string | null;
}) {
  return (
    <div className="flex w-64 cursor-grabbing flex-col gap-1 rounded-md bg-card px-3 py-2 text-sm shadow-overlay">
      <span className="flex items-center gap-2">
        <GripVertical className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
        <span className="flex-1">
          {typeof label === 'object' ? (
            <DishName type={label.type} displayName={label.displayName} />
          ) : (
            (label ?? '')
          )}
        </span>
      </span>
      {hint ? <span className="pl-6 text-xs font-medium text-primary">{hint}</span> : null}
    </div>
  );
}

/** Add dishes and desserts to one day; stays open so several can be added in a row. */
function DishPicker({
  date,
  day,
  dishes,
  desserts,
  onClose,
  onAddDish,
  onAddDessert,
}: {
  date: string | null;
  day: DraftDay | undefined;
  dishes: DishView[];
  desserts: DessertView[];
  onClose: () => void;
  onAddDish: (id: string) => void;
  onAddDessert: (id: string) => void;
}) {
  const onDay = new Set(day?.items.map((i) => i.dishId));
  const dessertsOnDay = new Set(day?.dessertIds);
  const title = date
    ? t('menu.pickerTitle', {
        day: `${vi.weekdays[isoWeekdayOf(date) as keyof typeof vi.weekdays]} ${formatDate(date)}`,
      })
    : '';

  // cmdk filters on `value`; ids keep two dishes with the same name apart.
  const entry = (
    id: string,
    label: string,
    added: boolean,
    onAdd: () => void,
    content?: React.ReactNode,
  ) => (
    <CommandItem key={id} value={`${label} ${id}`} disabled={added} onSelect={onAdd}>
      {added ? <Check className="text-primary" aria-hidden /> : <Plus aria-hidden />}
      <span className="flex-1">{content ?? label}</span>
      {added ? <span className="text-xs">{t('menu.added')}</span> : null}
    </CommandItem>
  );

  return (
    <AppDialog
      open={date !== null}
      onClose={onClose}
      title={title}
      className="gap-3"
      footer={<Button onClick={onClose}>{t('common.done')}</Button>}
    >
      <Command className="rounded-md shadow-block">
        <CommandInput placeholder={t('menu.searchDish')} />
        <CommandList className="max-h-[50vh]">
          <CommandEmpty>
            {dishes.length === 0 && desserts.length === 0
              ? t('menu.libraryEmpty')
              : t('menu.noDishFound')}
          </CommandEmpty>
          <CommandGroup heading={t('menu.mainDishes')}>
            {dishes.map((d) =>
              entry(
                d.id,
                d.displayName,
                onDay.has(d.id),
                () => onAddDish(d.id),
                <DishName type={d.type} name={d.name} />,
              ),
            )}
          </CommandGroup>
          <CommandGroup heading={t('menu.desserts')}>
            {desserts.map((d) =>
              entry(d.id, d.name, dessertsOnDay.has(d.id), () => onAddDessert(d.id)),
            )}
          </CommandGroup>
        </CommandList>
      </Command>
    </AppDialog>
  );
}

function DayColumn({
  day,
  store,
  published,
  wasServing,
  locked,
  onAdd,
  draggingDish,
  dishById,
  dessertName,
  replacementOptions,
  mixRules,
}: {
  day: DraftDay;
  store: MenuBuilderStore;
  published: boolean;
  wasServing: boolean;
  locked: boolean;
  onAdd: () => void;
  draggingDish: boolean;
  dishById: Map<string, DishLabel>;
  dessertName: Map<string, string>;
  replacementOptions: DishView[];
  mixRules: Parameters<typeof checkMenuMix>[1];
}) {
  const { setNodeRef, isOver } = useDroppable({ id: `day:${day.date}`, disabled: locked });
  const weekday = vi.weekdays[isoWeekdayOf(day.date) as keyof typeof vi.weekdays];
  const types = day.items
    .map((i) => dishById.get(i.dishId)?.type.id)
    .filter((x) => x !== undefined);
  const mix = day.isServing ? checkMenuMix(types, mixRules) : null;
  const actions = store.getState();
  // BR-08 / open-questions Q16: a published serving day cannot be turned off.
  const canToggle = !locked && !(published && wasServing && day.isServing);

  return (
    <section
      ref={setNodeRef}
      aria-label={`${weekday} ${formatDate(day.date)}`}
      className={cn(
        'flex flex-col gap-4 rounded-lg bg-card p-5 shadow-block transition-[box-shadow,background-color] sm:min-h-48',
        isOver && 'bg-accent shadow-raised ring-2 ring-primary/40',
        !day.isServing && 'bg-muted/60 shadow-none',
        locked && 'opacity-70',
      )}
    >
      <header className="flex items-start justify-between gap-2">
        <div>
          <p className="font-medium">{weekday}</p>
          <p className="text-xs text-muted-foreground">{formatDate(day.date)}</p>
        </div>
        {locked ? (
          <Badge variant="outline">{t('menu.locked')}</Badge>
        ) : canToggle ? (
          <Button
            variant="ghost"
            size="sm"
            className="gap-1"
            onClick={() => actions.setServing(day.date, !day.isServing)}
          >
            <CirclePower height={15} />
            {day.isServing ? t('menu.disableDay') : t('menu.enableDay')}
          </Button>
        ) : null}
      </header>

      {!day.isServing ? (
        <p className="text-sm text-muted-foreground">{t('menu.notServing')}</p>
      ) : (
        <>
          <ul className="flex flex-col gap-1">
            {day.items.map((item, index) => (
              <DayItem
                key={`${item.id ?? 'new'}:${item.dishId}`}
                date={day.date}
                index={index}
                dishId={item.dishId}
                saved={item.id !== null}
                published={published}
                locked={locked}
                draggingDish={draggingDish}
                store={store}
                dishById={dishById}
                replacementOptions={replacementOptions}
              />
            ))}
            {day.items.length === 0 ? (
              <li className="rounded-md bg-muted/50 px-2 py-3 text-center text-xs text-muted-foreground">
                {t('menu.emptyDay')}
              </li>
            ) : null}
          </ul>
          {day.dessertIds.length > 0 ? (
            <div className="flex flex-wrap gap-1">
              {day.dessertIds.map((id) => (
                <Badge key={id} variant="outline" className="gap-1">
                  {dessertName.get(id) ?? id}
                  {!locked ? (
                    <Button
                      variant="ghost"
                      size="icon"
                      className="-my-1 -mr-1.5 h-6 w-6"
                      aria-label={`${t('common.remove')} ${dessertName.get(id) ?? ''}`}
                      onClick={() => actions.removeDessert(day.date, id)}
                    >
                      <X className="h-3 w-3" aria-hidden />
                    </Button>
                  ) : null}
                </Badge>
              ))}
            </div>
          ) : null}
          {!locked ? (
            // Wide screens add by dragging from the library; narrower ones use this picker.
            <Button variant="outline" size="sm" className="self-start lg:hidden" onClick={onAdd}>
              <Plus className="h-4 w-4" aria-hidden />
              {t('menu.addDish')}
            </Button>
          ) : null}
          {/* BR-13: only a day that already has dishes gets the unusual-mix note. */}
          {mix && !mix.usual && day.items.length > 0 ? (
            <p className="rounded-md bg-warning/15 px-2 py-1 text-xs" role="status">
              {t('menu.mixWarning', {
                issues: mix.issues
                  .map((i) => t('menu.mixIssue', { type: i.typeName, actual: i.actual }))
                  .join(', '),
              })}
            </p>
          ) : null}
        </>
      )}
    </section>
  );
}

/**
 * One dish of a day. It is also a drop target: a library dish dropped on it replaces it, which
 * moves its registrations once the week is published (BR-14). Below 1024 px, where the library
 * is hidden, a published dish is swapped with the select instead.
 */
function DayItem({
  date,
  index,
  dishId,
  saved,
  published,
  locked,
  draggingDish,
  store,
  dishById,
  replacementOptions,
}: {
  date: string;
  index: number;
  dishId: string;
  saved: boolean;
  published: boolean;
  locked: boolean;
  draggingDish: boolean;
  store: MenuBuilderStore;
  dishById: Map<string, DishLabel>;
  replacementOptions: DishView[];
}) {
  const { setNodeRef, isOver } = useDroppable({ id: `item:${date}:${index}`, disabled: locked });
  const actions = store.getState();
  const dish = dishById.get(dishId);
  const replaceHere = isOver && draggingDish;

  return (
    <li
      ref={setNodeRef}
      className={cn(
        'flex flex-col gap-2 rounded-md bg-muted/60 px-3 py-2.5 text-sm transition-[box-shadow,background-color]',
        replaceHere && 'bg-primary/10 ring-2 ring-primary',
      )}
    >
      <div className="flex items-center gap-1">
        <span className="flex-1">
          {dish ? <DishName type={dish.type} displayName={dish.displayName} /> : dishId}
          {replaceHere ? (
            <span className="ml-2 inline-flex items-center gap-1 text-xs font-medium text-primary">
              <ArrowLeftRight className="h-3 w-3" aria-hidden />
              {t('menu.dropToReplace')}
            </span>
          ) : null}
        </span>
        {!locked ? (
          <>
            <Button
              variant="ghost"
              size="icon"
              className="h-8 w-8"
              aria-label={t('common.previous')}
              onClick={() => actions.moveItem(date, index, -1)}
            >
              <ArrowUp className="h-3 w-3" aria-hidden />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              className="h-8 w-8"
              aria-label={t('common.next')}
              onClick={() => actions.moveItem(date, index, 1)}
            >
              <ArrowDown className="h-3 w-3" aria-hidden />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              className="h-8 w-8"
              aria-label={t('common.remove')}
              onClick={() => actions.removeItem(date, index)}
            >
              <X className="h-4 w-4" aria-hidden />
            </Button>
          </>
        ) : null}
      </div>
      {published && saved && !locked ? (
        <div className="lg:hidden">
          <ResponsiveSelect
            aria-label={`${t('menu.replaceWith')}: ${dish?.displayName ?? ''}`}
            className="h-10 bg-card text-sm"
            value={dishId}
            onValueChange={(v) => actions.replaceItem(date, index, v)}
            options={[
              ...(!replacementOptions.some((o) => o.id === dishId)
                ? [{ value: dishId, label: dish?.displayName ?? dishId }]
                : []),
              ...replacementOptions.map((o) => ({
                value: o.id,
                label:
                  o.id === dishId ? o.displayName : `${t('menu.replaceWith')}: ${o.displayName}`,
              })),
            ]}
          />
        </div>
      ) : null}
    </li>
  );
}
