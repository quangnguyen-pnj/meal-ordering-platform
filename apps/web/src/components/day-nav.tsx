'use client';

import { addDays } from '@meal/shared';
import { ChevronLeft, ChevronRight, Undo2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { DatePicker } from '@/components/ui/date-picker';
import { Label } from '@/components/ui/label';
import { t } from '@/messages';

/**
 * Previous / next day around a date picker, and a jump back to today: the day-by-day screens
 * (/admin/today, /admin/reconciliation) look at today, and only now and then at a past day.
 * Days after `today` cannot be picked or reached with the arrow. `today` is the server's day,
 * never the browser's clock; while it is unknown the arrows and the jump stay disabled.
 */
export function DayNav({
  id,
  date,
  today,
  onChange,
}: {
  id: string;
  /** The day shown, YYYY-MM-DD, or '' while loading. */
  date: string;
  /** Today in Asia/Ho_Chi_Minh by the server's clock, YYYY-MM-DD. */
  today?: string;
  onChange: (date: string) => void;
}) {
  const ready = !!date && !!today;
  return (
    <div className="flex flex-wrap items-center gap-2">
      {/* Always there, only disabled on today, like the week nav: the arrows never move. */}
      <Button
        variant="tertiary"
        size="sm"
        className="mr-2 disabled:text-muted-foreground"
        disabled={!ready || date === today}
        onClick={() => today && onChange(today)}
      >
        <Undo2 className="h-4 w-4" aria-hidden />
        <span className="sr-only sm:not-sr-only">{t('common.dayNav.today')}</span>
      </Button>
      <Button
        variant="ghost"
        size="icon"
        aria-label={t('common.dayNav.previous')}
        disabled={!date}
        onClick={() => onChange(addDays(date, -1))}
      >
        <ChevronLeft className="h-4 w-4" aria-hidden />
      </Button>
      <Label htmlFor={id} className="sr-only">
        {t('common.dayNav.label')}
      </Label>
      <DatePicker
        id={id}
        className="w-40"
        value={date}
        onChange={onChange}
        max={today}
        placeholder={t('common.dayNav.placeholder')}
      />
      <Button
        variant="ghost"
        size="icon"
        aria-label={t('common.dayNav.next')}
        disabled={!ready || date >= today}
        onClick={() => onChange(addDays(date, 1))}
      >
        <ChevronRight className="h-4 w-4" aria-hidden />
      </Button>
    </div>
  );
}
