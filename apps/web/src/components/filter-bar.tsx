'use client';

import { Check, FilterX, ListFilter, SlidersHorizontal } from 'lucide-react';
import type * as React from 'react';
import { useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet';
import { t } from '@/messages';

/** Rendered once per place it shows in (the row, the phone sheet, "more"); ids get the prefix. */
type Fields = (prefix: string) => React.ReactNode;

function CountBadge({ count }: { count: number }) {
  return count > 0 ? (
    <Badge variant="default" className="ml-1 px-1.5 py-0 tabular-nums">
      {count}
    </Badge>
  ) : null;
}

/**
 * The one filter row above a list, in a fixed order: search, the filters, "Xoá lọc" when
 * something is set, then the list's own actions on the right. Phones keep the search and a
 * "Bộ lọc" button; the filters open in a bottom sheet so the list starts on the first screen.
 * Filters that are rarely used go in `more`: a "Thêm bộ lọc" popover on wide screens.
 */
export function FilterBar({
  search,
  filters,
  more,
  activeCount = 0,
  moreCount = 0,
  searching = false,
  onClear,
  actions,
  doneLabel,
}: {
  search?: React.ReactNode;
  filters?: Fields;
  more?: Fields;
  /** Filters set in `filters` and `more` (not the search): the phone button's count. */
  activeCount?: number;
  /** Filters set in `more`: the "Thêm bộ lọc" button's count. */
  moreCount?: number;
  /** The search box holds text: "Xoá lọc" shows for it too. */
  searching?: boolean;
  /** Clears the filters and the search. */
  onClear?: () => void;
  actions?: React.ReactNode;
  /** The sheet's closing button, e.g. "Xem 12 người"; "Xong" by default. */
  doneLabel?: string;
}) {
  const [sheetOpen, setSheetOpen] = useState(false);
  const hasFilters = Boolean(filters || more);
  const canClear = Boolean(onClear) && (activeCount > 0 || searching);

  const phoneButton = hasFilters ? (
    <Button
      variant="outline"
      className="shrink-0 md:hidden"
      aria-haspopup="dialog"
      onClick={() => setSheetOpen(true)}
    >
      <SlidersHorizontal className="h-4 w-4" aria-hidden />
      {t('common.filters')}
      <CountBadge count={activeCount} />
    </Button>
  ) : null;

  return (
    <>
      <div
        role="group"
        aria-label={t('common.filters')}
        className="flex flex-wrap items-center gap-x-3 gap-y-4"
      >
        {search ? (
          <div className="flex w-full min-w-0 gap-2 md:w-64">
            <div className="min-w-0 flex-1">{search}</div>
            {phoneButton}
          </div>
        ) : (
          phoneButton
        )}
        {filters ? <div className="hidden md:contents">{filters('f')}</div> : null}
        {more ? (
          <Popover>
            <PopoverTrigger asChild>
              <Button variant="outline" className="hidden md:inline-flex">
                <ListFilter className="h-4 w-4" aria-hidden />
                {t('common.moreFilters')}
                <CountBadge count={moreCount} />
              </Button>
            </PopoverTrigger>
            <PopoverContent align="start" className="flex flex-col gap-5 pt-6 [&>div]:w-full">
              {more('fp')}
            </PopoverContent>
          </Popover>
        ) : null}
        {canClear ? (
          <Button variant="tertiary" size="sm" className="hidden md:inline-flex" onClick={onClear}>
            <FilterX className="h-4 w-4" aria-hidden />
            {t('common.clearFilters')}
          </Button>
        ) : null}
        {actions ? (
          <div className="flex w-full flex-wrap gap-2 md:ml-auto md:w-auto">{actions}</div>
        ) : null}
      </div>

      {hasFilters ? (
        <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
          <SheetContent side="bottom" className="max-h-[85dvh] gap-5 rounded-t-xl p-5 md:hidden">
            <SheetHeader>
              <SheetTitle>{t('common.filters')}</SheetTitle>
              <SheetDescription>{t('common.filtersHint')}</SheetDescription>
            </SheetHeader>
            {/* pt-2: room for the first field's name, which sits on its top edge. */}
            <div className="-mx-5 flex flex-col gap-5 overflow-y-auto px-5 pt-2 [&>div]:w-full">
              {filters?.('fm')}
              {more?.('fmm')}
            </div>
            <div className="grid grid-cols-2 gap-2">
              <Button variant="outline" disabled={!canClear} onClick={onClear}>
                <FilterX className="h-4 w-4" aria-hidden />
                {t('common.clearFilters')}
              </Button>
              <Button onClick={() => setSheetOpen(false)}>
                <Check className="h-4 w-4" aria-hidden />
                {doneLabel ?? t('common.done')}
              </Button>
            </div>
          </SheetContent>
        </Sheet>
      ) : null}
    </>
  );
}
