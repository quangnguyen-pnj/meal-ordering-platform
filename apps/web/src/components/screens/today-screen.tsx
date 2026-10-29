'use client';

import type { MyDayRegistrationView, SaveRegistrationResult } from '@/lib/api-types';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { toast } from 'sonner';
import { useConfirm } from '@/components/confirm-dialog';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { LoadingRows } from '@/components/ui/skeleton';
import { api } from '@/lib/api';
import { apiErrorText } from '@/lib/errors';
import { linesOf } from '@/lib/registration-draft';
import { formatDate, formatVnTime } from '@/lib/utils';
import { t } from '@/messages';
import { DayMeals, weekdayName } from './day-meals';

const KEY = ['/registrations/me/day'];

/** Minutes until `lockAt`, counted from the server's `now` plus time elapsed on this page. */
function useMinutesLeft(lockAt: string | null, serverNow: string | null): number | null {
  const [elapsed, setElapsed] = useState(0);
  useEffect(() => {
    const started = performance.now();
    setElapsed(0);
    const id = setInterval(() => setElapsed(performance.now() - started), 15_000);
    return () => clearInterval(id);
  }, [serverNow]);
  if (!lockAt || !serverNow) return null;
  const ms = Date.parse(lockAt) - (Date.parse(serverNow) + elapsed);
  return Math.max(0, Math.ceil(ms / 60_000));
}

export function formatMinutes(total: number): string {
  const hours = Math.floor(total / 60);
  const minutes = total % 60;
  return hours > 0 ? t('today.hoursMinutes', { hours, minutes }) : t('today.minutes', { minutes });
}

/**
 * /today (BR-23, BR-24): today's forecast and current meals, desserts and the countdown to the
 * lock; add, change or "Not eating today" until then, read-only afterwards with a hint to contact
 * an admin. Attendance status and the leave flag join in P3.
 */
export function TodayScreen() {
  const qc = useQueryClient();
  const query = useQuery({
    queryKey: KEY,
    queryFn: () => api.get<MyDayRegistrationView>('/registrations/me/day'),
  });
  const data = query.data;
  const view = data?.state === 'DAY' ? data : null;
  const [quantities, setQuantities] = useState<Record<string, number>>({});
  const [confirm, confirmDialog] = useConfirm();
  useEffect(() => {
    if (view) {
      setQuantities(Object.fromEntries(view.day.items.map((i) => [i.menuItemId, i.quantity])));
    }
  }, [view]);
  const left = useMinutesLeft(view?.day.lockAt ?? null, view?.now ?? null);

  const save = useMutation({
    mutationFn: (q: Record<string, number>) =>
      api.put<SaveRegistrationResult<MyDayRegistrationView>>(
        `/registrations/me/days/${view!.date}`,
        { lines: linesOf(q) },
      ),
    onSuccess: (res) => {
      qc.setQueryData(KEY, res.view);
      toast.success(t('common.saved'));
    },
  });

  const day = view?.day;
  const locked = !day || day.window === 'LOCKED' || left === 0;
  const dirty = !!day && day.items.some((i) => (quantities[i.menuItemId] ?? 0) !== i.quantity);
  const total = Object.values(quantities).reduce((s, n) => s + n, 0);

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('today.title')}</CardTitle>
        <CardDescription>
          {data ? `${weekdayName(data.date)} ${formatDate(data.date)} · ` : ''}
          {t('today.intro')}
        </CardDescription>
      </CardHeader>

      <CardContent className="flex flex-col gap-6">
        {confirmDialog}
        {query.error ? <Alert variant="destructive">{apiErrorText(query.error)}</Alert> : null}
        {save.error ? <Alert variant="destructive">{apiErrorText(save.error)}</Alert> : null}
        {query.isLoading ? <LoadingRows rows={3} /> : null}
        {data?.state === 'UNASSIGNED' ? (
          <Alert variant="warning">{t('week.unassigned')}</Alert>
        ) : null}
        {data?.state === 'NO_MENU' ? (
          <Alert>{t('today.notServing', { canteen: data.canteenName })}</Alert>
        ) : null}

        {view && day ? (
          <>
            <dl className="grid grid-cols-2 gap-3 sm:max-w-md">
              <div className="rounded-lg bg-card p-4 shadow-block">
                <dt className="text-xs text-muted-foreground">{t('today.forecastLabel')}</dt>
                <dd className="text-xl font-semibold tabular-nums">
                  {day.forecastTotal === null
                    ? t('today.noForecast')
                    : t('today.meals', { count: day.forecastTotal })}
                </dd>
              </div>
              <div className="rounded-lg bg-card p-4 shadow-block">
                <dt className="text-xs text-muted-foreground">{t('today.actualLabel')}</dt>
                <dd className="text-xl font-semibold tabular-nums">
                  {t('today.meals', { count: day.actualTotal })}
                </dd>
              </div>
            </dl>

            {!locked && left !== null ? (
              <Alert role="timer">
                {t('today.countdown', {
                  left: formatMinutes(left),
                  lock: formatVnTime(day.lockAt),
                })}
              </Alert>
            ) : null}

            <section className="max-w-xl rounded-lg bg-card p-4 shadow-block">
              <DayMeals
                day={locked ? { ...day, window: 'LOCKED' } : day}
                quantities={quantities}
                onChange={(id, q) => setQuantities((prev) => ({ ...prev, [id]: q }))}
              />
            </section>

            {!locked ? (
              <div className="flex flex-wrap gap-2">
                <Button disabled={!dirty || save.isPending} onClick={() => save.mutate(quantities)}>
                  {save.isPending ? t('common.saving') : t('common.save')}
                </Button>
                <Button
                  variant="outline"
                  disabled={save.isPending || (total === 0 && day.actualTotal === 0)}
                  onClick={async () => {
                    const ok = await confirm({
                      title: t('today.notEatingConfirm'),
                      confirmLabel: t('today.notEating'),
                      destructive: true,
                    });
                    if (!ok) return;
                    const none = Object.fromEntries(day.items.map((i) => [i.menuItemId, 0]));
                    setQuantities(none);
                    save.mutate(none);
                  }}
                >
                  {t('today.notEating')}
                </Button>
              </div>
            ) : null}
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}
