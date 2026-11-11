'use client';

import { addDays } from '@meal/shared';
import { ChevronLeft, ChevronRight, Undo2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { formatWeekNumber, formatWeekRange } from '@/lib/utils';
import { t } from '@/messages';

/** Which week this is relative to the current one, or null further away. */
function relativeWeekLabel(weekStart: string, currentWeekStart?: string): string | null {
  if (!currentWeekStart) return null;
  if (weekStart === currentWeekStart) return t('common.weekRelative.current');
  if (weekStart === addDays(currentWeekStart, 7)) return t('common.weekRelative.next');
  if (weekStart === addDays(currentWeekStart, -7)) return t('common.weekRelative.previous');
  return null;
}

/** Previous / next week with its ISO week number and Monday–Sunday range, and a jump back to the default week. */
export function WeekNav({
  weekStart,
  onChange,
  homeWeekStart,
  currentWeekStart,
}: {
  weekStart: string;
  onChange: (weekStart: string) => void;
  /** Monday of the current week by server time; names the week being viewed ("tuần hiện tại"). */
  currentWeekStart?: string;
  /** The week the screen opens on; a "back" link appears when the user has moved away. */
  homeWeekStart?: string;
}) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      {homeWeekStart ? (
        // Always there, only disabled on the home week: a button that comes and goes would move the
        // arrows under the user's finger. Spacing, not a divider line, sets it apart from them.
        // Phones keep the icon alone so the whole control stays on one line.
        <Button
          variant="tertiary"
          size="sm"
          className="mr-2 disabled:text-muted-foreground"
          disabled={homeWeekStart === weekStart}
          onClick={() => onChange(homeWeekStart)}
        >
          <Undo2 className="h-4 w-4" aria-hidden />
          <span className="sr-only sm:not-sr-only">{t('menu.backToUpcoming')}</span>
        </Button>
      ) : null}

      <Button
        variant="ghost"
        size="icon"
        aria-label={t('menu.previousWeek')}
        onClick={() => onChange(addDays(weekStart, -7))}
      >
        <ChevronLeft className="h-4 w-4" aria-hidden />
      </Button>
      <p
        className="flex min-w-44 flex-col items-center text-center text-sm font-medium tabular-nums"
        aria-live="polite"
      >
        <span className="text-xs font-semibold text-primary">
          {formatWeekNumber(weekStart)}
          {relativeWeekLabel(weekStart, currentWeekStart)
            ? ` · ${relativeWeekLabel(weekStart, currentWeekStart)}`
            : ''}
        </span>
        <span>{formatWeekRange(weekStart)}</span>
      </p>
      <Button
        variant="ghost"
        size="icon"
        aria-label={t('menu.nextWeek')}
        onClick={() => onChange(addDays(weekStart, 7))}
      >
        <ChevronRight className="h-4 w-4" aria-hidden />
      </Button>
    </div>
  );
}
