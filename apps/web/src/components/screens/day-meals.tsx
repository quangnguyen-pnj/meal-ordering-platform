'use client';

import { isoWeekdayOf } from '@meal/shared';
import type { MyDayView } from '@/lib/api-types';
import { CircleAlert } from 'lucide-react';
import { DishName } from '@/components/dish-name';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { QuantityStepper } from '@/components/ui/quantity-stepper';
import { formatDate, formatVnDateTime, formatVnTime } from '@/lib/utils';
import { t, vi } from '@/messages';

export const weekdayName = (date: string) =>
  vi.weekdays[isoWeekdayOf(date) as keyof typeof vi.weekdays];

/** "T2" … "CN", for day tabs on phones. */
export const weekdayShort = (date: string) =>
  vi.weekdaysShort[isoWeekdayOf(date) as keyof typeof vi.weekdaysShort];

/** Status line of a day for the employee (BR-22 to BR-24). */
export function DayWindowBadge({ day }: { day: MyDayView }) {
  if (day.window === 'LOCKED') return <Badge variant="outline">{t('week.window.LOCKED')}</Badge>;
  // Before closeAt the banner already gives the deadline; only later states need a badge.
  if (day.window === 'OPEN') return null;
  return (
    <Badge variant="warning">
      {t('week.window.AFTER_CLOSE', {
        lock: `${formatVnTime(day.lockAt)} ${formatDate(day.date)}`,
      })}
    </Badge>
  );
}

/**
 * One serving day's meals with a stepper per meal (BR-20), the forecast once taken (BR-07,
 * BR-23), meals waiting for reselection (BR-14), a red note on a dish that replaced the one the
 * employee chose after closeAt (BR-14, 2026-10-23), and desserts. Shared by /week and /today;
 * only /week's "Thực tế" tab shows the forecast per dish.
 */
export function DayMeals({
  day,
  quantities,
  onChange,
  showForecast = false,
}: {
  day: MyDayView;
  quantities: Record<string, number>;
  onChange: (menuItemId: string, quantity: number) => void;
  /** /week "Thực tế" tab: each dish's forecast and the difference from it (BR-23). */
  showForecast?: boolean;
}) {
  const locked = day.window === 'LOCKED';
  return (
    <div className="flex flex-col gap-3">
      {day.needsReselect.map((n) => (
        <Alert key={n.displayName} variant="warning">
          {t('week.needsReselect', {
            dish: n.displayName,
            quantity: n.quantity,
            lock: formatVnDateTime(day.lockAt),
          })}
        </Alert>
      ))}

      <ul className="flex flex-col gap-3">
        {day.items.map((item) => {
          const value = quantities[item.menuItemId] ?? 0;
          return (
            <li key={item.menuItemId} className="flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="text-sm font-medium">
                  <DishName type={item.type} displayName={item.displayName} />
                </p>

                {item.replacedAfterClose ? (
                  <p className="mt-0.5 flex items-start gap-1 text-xs font-medium text-destructive">
                    <CircleAlert className="mt-px h-3.5 w-3.5 shrink-0" aria-hidden />
                    {t('week.replacedAfterClose', { from: item.replacedAfterClose.from })}
                  </p>
                ) : null}

                {showForecast && day.forecastTotal !== null ? (
                  <p className="text-xs text-muted-foreground">
                    {t('week.itemForecast', { count: item.forecast })}
                    {value !== item.forecast ? (
                      <span className="ml-1.5 font-medium text-foreground">
                        ({value > item.forecast ? '+' : ''}
                        {value - item.forecast})
                      </span>
                    ) : null}
                  </p>
                ) : null}
              </div>

              {locked ? (
                <span className="!w-14 text-center text-base font-medium tabular-nums">
                  {value}
                </span>
              ) : (
                <QuantityStepper
                  value={value}
                  onChange={(q) => onChange(item.menuItemId, q)}
                  label={t('week.quantityOf', { dish: item.displayName })}
                  increaseLabel={t('week.increase', { dish: item.displayName })}
                  decreaseLabel={t('week.decrease', { dish: item.displayName })}
                />
              )}
            </li>
          );
        })}
      </ul>

      <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <span>
          {day.desserts.length > 0
            ? t('week.dessert', { list: day.desserts.join(', ') })
            : t('week.noDessert')}
        </span>
        {day.forecastTotal !== null ? (
          <span>· {t('week.forecast', { count: day.forecastTotal })}</span>
        ) : null}
        {/* {day.changed ? <Badge variant="warning">{t('week.changed')}</Badge> : null} */}
        {day.extra ? <Badge variant="secondary">{t('week.extra')}</Badge> : null}
      </div>
    </div>
  );
}
