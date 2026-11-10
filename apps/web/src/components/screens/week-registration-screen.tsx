'use client';

import type { MyRegistrationWeekView, SaveRegistrationResult } from '@/lib/api-types';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CalendarClock, Clock, Lock, Save, Undo2, UtensilsCrossed } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';
import { useConfirm } from '@/components/confirm-dialog';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { LoadingRows } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { WeekNav } from '@/components/week-nav';
import { api } from '@/lib/api';
import { apiErrorText } from '@/lib/errors';
import {
  changedDates,
  dayTotal,
  draftFromDays,
  saveBody,
  setQuantity,
  type Draft,
} from '@/lib/registration-draft';
import { cn, formatCloseAt, formatDayMonth, formatVnTime } from '@/lib/utils';
import { t } from '@/messages';
import { DayMeals, DayWindowBadge, weekdayName } from './day-meals';
import { ForecastWeekTable } from './forecast-week-table';

/** A short fact with an icon, in place of a sentence. */
const chip =
  'inline-flex items-center gap-1.5 text-xs font-semibold rounded-full bg-muted px-3 py-1 [&_svg]:h-4 [&_svg]:w-4 [&_svg]:shrink-0';

const weekUrl = (weekStart: string | null) =>
  `/registrations/me/week${weekStart ? `?weekStart=${weekStart}` : ''}`;

type Tab = 'forecast' | 'actual';

/**
 * /week (J1): one card per serving day with a stepper per meal and no cap (BR-20), the closeAt
 * banner (BR-03). The BR-26 shortcuts were removed (product owner, 2026-10-23).
 * Two tabs (product owner, 2026-10-27): before closeAt only "Dự trù tuần", where the employee
 * registers. Once the forecast is taken (BR-07) "Dự trù tuần" is the read-only snapshot and
 * "Thực tế" holds the day cards, each editable until its own lock and showing its difference
 * from the forecast (BR-23).
 */
export function WeekRegistrationScreen() {
  const qc = useQueryClient();
  const [weekStart, setWeekStart] = useState<string | null>(null);
  const query = useQuery({
    queryKey: ['/registrations/me/week', weekStart],
    queryFn: () => api.get<MyRegistrationWeekView>(weekUrl(weekStart)),
  });
  const data = query.data;
  const published = data?.state === 'PUBLISHED' ? data : null;
  const current = weekStart ?? data?.weekStart ?? null;
  // The week the API picked on first load (J1); WeekNav offers a way back to it.
  const [homeWeek, setHomeWeek] = useState<string | null>(null);
  if (!homeWeek && !weekStart && current) setHomeWeek(current);
  // Kept across week changes so the label does not flicker while the next week loads.
  const [thisWeek, setThisWeek] = useState<string | null>(null);
  if (data?.currentWeekStart && data.currentWeekStart !== thisWeek) {
    setThisWeek(data.currentWeekStart);
  }
  const [confirm, confirmDialog] = useConfirm();
  const [selectedTab, setSelectedTab] = useState<Tab>('actual');
  const forecastTaken = published?.forecastTaken ?? false;
  const tab: Tab = forecastTaken ? selectedTab : 'forecast';

  const [draft, setDraft] = useState<Draft>({});
  useEffect(() => {
    setDraft(published ? draftFromDays(published.days) : {});
  }, [published]);

  const days = useMemo(() => published?.days ?? [], [published]);
  const changed = changedDates(days, draft);
  const total = days.reduce((s, d) => s + dayTotal(draft, d.date), 0);

  const save = useMutation({
    mutationFn: () =>
      api.put<SaveRegistrationResult<MyRegistrationWeekView>>(
        '/registrations/me/week',
        saveBody(published!.weekStart, days, draft),
      ),
    onSuccess: (res) => {
      qc.setQueryData(['/registrations/me/week', weekStart], res.view);
      if (res.view.state === 'PUBLISHED') {
        toast.success(
          t('week.saved', { meals: res.view.totalMeals, time: formatCloseAt(res.view.closeAt) }),
        );
      }
    },
  });

  async function go(next: string) {
    if (
      changed.length > 0 &&
      !(await confirm({ title: t('week.leaveConfirm'), confirmLabel: t('week.discard') }))
    )
      return;
    setWeekStart(next);
    setSelectedTab('actual');
  }

  const error = query.error ?? save.error;
  const lockTime = days[0] ? formatVnTime(days[0].lockAt) : '';

  // The day cards and the save bar: the "Dự trù tuần" tab before closeAt, "Thực tế" after.
  const editor = (
    <>
      <div className="grid gap-4 md:grid-cols-2 2xl:grid-cols-3">
        {days.map((day) => (
          <section
            key={day.date}
            className="flex flex-col gap-4 rounded-lg bg-card p-4 shadow-block sm:p-5"
          >
            <header className="flex flex-wrap items-center justify-between gap-2">
              <h2 className="font-medium flex items-center gap-2">
                {weekdayName(day.date)}{' '}
                <span className="font-medium text-sm text-muted-foreground">
                  ( {formatDayMonth(day.date)} )
                </span>
              </h2>
              <DayWindowBadge day={day} />
            </header>
            <DayMeals
              day={day}
              quantities={draft[day.date] ?? {}}
              onChange={(itemId, q) => setDraft((d) => setQuantity(d, day.date, itemId, q))}
              showForecast={forecastTaken}
            />
            <footer className="-mx-4 -mb-4 mt-auto flex flex-wrap items-center justify-between gap-2 rounded-b-lg bg-muted/50 px-4 py-2.5 sm:-mx-5 sm:-mb-5 sm:px-5">
              <span className="text-sm font-medium">
                {t('week.dayTotal', { count: dayTotal(draft, day.date) })}
              </span>
            </footer>
          </section>
        ))}
      </div>

      <div className="sticky bottom-3 z-20 flex flex-wrap items-center justify-between gap-3 rounded-xl bg-card px-4 py-3 shadow-raised">
        <div className="text-sm">
          <p>
            <span className="text-base font-semibold tabular-nums">{total}</span>{' '}
            {t('week.totalMeals')}
          </p>
          <p className="text-muted-foreground">
            {changed.length > 0 ? t('week.unsaved') : t('week.noChanges')}
          </p>
        </div>
        <div className="flex gap-2">
          <Button
            variant="outline"
            disabled={changed.length === 0 || save.isPending}
            onClick={() => setDraft(draftFromDays(days))}
          >
            <Undo2 className="h-4 w-4" aria-hidden />
            {t('week.discard')}
          </Button>
          <Button disabled={changed.length === 0 || save.isPending} onClick={() => save.mutate()}>
            <Save className="h-4 w-4" aria-hidden />
            {save.isPending ? t('common.saving') : t('week.save')}
          </Button>
        </div>
      </div>
    </>
  );

  return (
    <Card variant="page">
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-col gap-1.5">
            <CardTitle>{t('week.title')}</CardTitle>
            <CardDescription>{t('week.intro')}</CardDescription>
          </div>
          {current ? (
            <WeekNav
              weekStart={current}
              onChange={(next) => void go(next)}
              homeWeekStart={homeWeek ?? undefined}
              currentWeekStart={thisWeek ?? undefined}
            />
          ) : null}
        </div>
      </CardHeader>

      <CardContent className="flex flex-col gap-6">
        {confirmDialog}
        {error ? <Alert variant="destructive">{apiErrorText(error)}</Alert> : null}
        {query.isLoading ? <LoadingRows rows={4} /> : null}
        {data?.state === 'UNASSIGNED' ? (
          <Alert variant="warning">{t('week.unassigned')}</Alert>
        ) : null}
        {data?.state === 'NOT_PUBLISHED' ? (
          <Alert>{t('week.notPublished', { canteen: data.canteenName })}</Alert>
        ) : null}

        {published ? (
          <>
            {/* Short facts as chips: where you eat, the week's deadline, and the daily cut-off. */}
            <ul className="flex flex-wrap gap-2 text-sm">
              <li className={chip}>
                <UtensilsCrossed aria-hidden />
                <span className="sr-only">{t('week.canteen')}:</span>
                {published.canteenName}
              </li>
              <li
                className={cn(
                  chip,
                  !published.noForecast && !forecastTaken && 'bg-primary/10 text-primary',
                )}
              >
                {published.noForecast || forecastTaken ? (
                  <Lock aria-hidden />
                ) : (
                  <CalendarClock aria-hidden />
                )}
                {published.noForecast
                  ? t('week.noForecast')
                  : forecastTaken
                    ? t('week.afterClose')
                    : t('week.beforeClose', { time: formatCloseAt(published.closeAt) })}
              </li>
              {lockTime ? (
                <li className={chip}>
                  <Clock aria-hidden />
                  {t('week.dailyLock', { lock: lockTime })}
                </li>
              ) : null}
            </ul>

            <Tabs value={tab} onValueChange={(v) => setSelectedTab(v as Tab)}>
              <TabsList aria-label={t('week.tabs.label')}>
                <TabsTrigger value="forecast">{t('week.tabs.forecast')}</TabsTrigger>
                {/* Shown but disabled until the forecast is taken (was hidden, product owner 2026-10-27). */}
                <TabsTrigger value="actual" disabled={!forecastTaken}>
                  {t('week.tabs.actual')}
                </TabsTrigger>
              </TabsList>
              <TabsContent
                value="forecast"
                className={`flex flex-col ${forecastTaken ? 'gap-4' : 'gap-6'}`}
              >
                {forecastTaken ? (
                  <>
                    {published.noForecast ? (
                      <p className="text-sm text-muted-foreground">
                        {t('week.forecastTable.noForecast')}
                      </p>
                    ) : (
                      <p className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                        <Badge variant="outline">
                          <Lock aria-hidden />
                          {t('week.forecastTable.closedAt', {
                            time: formatCloseAt(published.closeAt),
                          })}
                        </Badge>
                        {t('week.forecastTable.intro')}
                      </p>
                    )}
                    <ForecastWeekTable days={days} />
                  </>
                ) : (
                  editor
                )}
              </TabsContent>
              {forecastTaken ? (
                <TabsContent value="actual" className="flex flex-col gap-6">
                  {editor}
                </TabsContent>
              ) : null}
            </Tabs>
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}
