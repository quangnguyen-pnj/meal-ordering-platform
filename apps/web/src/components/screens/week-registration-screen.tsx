'use client';

import type { MyRegistrationWeekView, SaveRegistrationResult } from '@/lib/api-types';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';
import { useConfirm } from '@/components/confirm-dialog';
import { Alert } from '@/components/ui/alert';
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
import { formatCloseAt, formatDate, formatVnTime } from '@/lib/utils';
import { t } from '@/messages';
import { DayMeals, DayWindowBadge, weekdayName } from './day-meals';
import { ForecastWeekTable } from './forecast-week-table';

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
              <h2 className="font-medium">
                {weekdayName(day.date)}{' '}
                <span className="text-xs font-normal text-muted-foreground">
                  ( {formatDate(day.date)} )
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
            <footer className="mt-auto flex flex-wrap items-center justify-between gap-2 border-t border-border/60 pt-2">
              <span className="text-sm font-medium">
                {t('week.dayTotal', { count: dayTotal(draft, day.date) })}
              </span>
            </footer>
          </section>
        ))}
      </div>

      <div className="sticky bottom-0 z-20 -mx-5 flex flex-wrap items-center justify-between gap-3 bg-card px-5 py-3 sm:-mx-6 sm:px-6 shadow-[0_-6px_16px_-6px_rgb(41_37_36/0.12)]">
        <div className="text-sm">
          <p className="font-medium">{t('week.totalMeals', { count: total })}</p>
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
            {t('week.discard')}
          </Button>
          <Button disabled={changed.length === 0 || save.isPending} onClick={() => save.mutate()}>
            {save.isPending ? t('common.saving') : t('week.save')}
          </Button>
        </div>
      </div>
    </>
  );

  return (
    <Card>
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
            <Alert variant={'default'}>
              <p>{t('week.canteen', { canteen: published.canteenName })}</p>
              <p className="font-medium">
                {forecastTaken
                  ? t('week.afterClose', { lock: lockTime })
                  : t('week.beforeClose', {
                      time: formatCloseAt(published.closeAt),
                      lock: lockTime,
                    })}
              </p>
            </Alert>

            <Tabs value={tab} onValueChange={(v) => setSelectedTab(v as Tab)}>
              <TabsList aria-label={t('week.tabs.label')}>
                <TabsTrigger value="forecast">{t('week.tabs.forecast')}</TabsTrigger>
                {/* Hidden until the forecast is taken (product owner, 2026-10-27). */}
                {forecastTaken ? (
                  <TabsTrigger value="actual">{t('week.tabs.actual')}</TabsTrigger>
                ) : null}
              </TabsList>
              <TabsContent
                value="forecast"
                className={`flex flex-col ${forecastTaken ? 'gap-4' : 'gap-6'}`}
              >
                {forecastTaken ? (
                  <>
                    <p className="text-sm text-muted-foreground">
                      {t('week.forecastTable.intro', { time: formatCloseAt(published.closeAt) })}
                    </p>
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
