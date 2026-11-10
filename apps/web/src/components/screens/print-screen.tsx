'use client';

import { Printer } from 'lucide-react';
import { useState } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { LoadingRows } from '@/components/ui/skeleton';
import { WeekNav } from '@/components/week-nav';
import { apiErrorText } from '@/lib/errors';
import { formatCloseAt, formatDate } from '@/lib/utils';
import { t } from '@/messages';
import { weekdayName } from './day-meals';
import {
  CanteenPicker,
  UnitPicker,
  useRegistrationGrid,
  useRegistrationUnits,
} from './registration-admin-shared';

/**
 * /admin/print (J2): paper forms for teams without phones. One table per serving day: rows are
 * employees, columns are meals, blank cells for counts. The layout matches the bulk-entry grid
 * so the dept admin types it in line by line. Printed with the browser's print CSS.
 */
export function PrintScreen() {
  const units = useRegistrationUnits();
  const [weekStart, setWeekStart] = useState<string | null>(null);
  const [unitId, setUnitId] = useState('');
  const [canteenId, setCanteenId] = useState('');
  const home = units.data?.upcomingWeekStart;
  const week = weekStart ?? home ?? null;
  const grid = useRegistrationGrid(unitId, week, canteenId);
  const view = grid.data;

  return (
    <div className="flex flex-col gap-5">
      <Card variant="page" className="print:hidden">
        <CardHeader>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex flex-col gap-1.5">
              <CardTitle>{t('print.title')}</CardTitle>
              <CardDescription>{t('print.intro')}</CardDescription>
            </div>
            {week ? (
              <WeekNav
                weekStart={week}
                onChange={setWeekStart}
                homeWeekStart={home}
                currentWeekStart={units.data?.currentWeekStart}
              />
            ) : null}
          </div>
        </CardHeader>
        <CardContent className="flex flex-col gap-6">
          {units.error ? <Alert variant="destructive">{apiErrorText(units.error)}</Alert> : null}
          <div className="flex flex-wrap items-end gap-4">
            <UnitPicker
              id="print-unit"
              units={units.data?.items ?? []}
              value={unitId}
              onChange={(id) => {
                setUnitId(id);
                setCanteenId('');
              }}
            />
            <CanteenPicker id="print-canteen" grid={view} onChange={setCanteenId} />
            <Button disabled={view?.state !== 'PUBLISHED'} onClick={() => window.print()}>
              <Printer className="h-4 w-4" aria-hidden />
              {t('print.print')}
            </Button>
          </div>
          {grid.error ? <Alert variant="destructive">{apiErrorText(grid.error)}</Alert> : null}
          {grid.isLoading ? <LoadingRows rows={3} /> : null}
          {view?.state === 'NOT_PUBLISHED' ? (
            <Alert>{t('week.notPublished', { canteen: view.canteenName ?? '' })}</Alert>
          ) : null}
          {view?.state === 'NO_EMPLOYEES' ? <Alert>{t('registrations.noEmployees')}</Alert> : null}
        </CardContent>
      </Card>

      {view?.state === 'PUBLISHED' ? (
        <div className="flex flex-col gap-8 print:gap-0">
          {view.days.map((day) => (
            <section
              key={day.date}
              className="break-after-page rounded-xl bg-card p-6 shadow-block print:rounded-none print:p-0 print:shadow-none"
            >
              <header className="mb-3 text-center">
                <p className="text-base font-bold">{t('print.heading')}</p>
                <p className="text-sm">
                  {t('print.dayLine', {
                    weekday: weekdayName(day.date),
                    date: formatDate(day.date),
                  })}
                </p>
                <p className="text-sm">
                  {t('print.unitLine', {
                    unit: view.orgUnit.pathName,
                    canteen: view.canteenName ?? '',
                  })}
                </p>
                {view.closeAt ? (
                  <p className="text-xs">
                    {t('print.deadline', { time: formatCloseAt(view.closeAt) })}
                  </p>
                ) : null}
              </header>
              <table className="w-full border-collapse text-sm">
                <thead>
                  <tr>
                    <th className="border border-foreground px-1 py-1 text-center">
                      {t('print.no')}
                    </th>
                    <th className="border border-foreground px-1 py-1 text-left">
                      {t('common.code')}
                    </th>
                    <th className="border border-foreground px-1 py-1 text-left">
                      {t('common.name')}
                    </th>
                    {day.items.map((i) => (
                      <th
                        key={i.id}
                        className="border border-foreground px-1 py-1 text-center text-xs"
                      >
                        {i.displayName}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {view.rows.map((row, n) => (
                    <tr key={row.employeeCode} className="h-8">
                      <td className="border border-foreground px-1 text-center tabular-nums">
                        {n + 1}
                      </td>
                      <td className="border border-foreground px-1 tabular-nums">
                        {row.employeeCode}
                      </td>
                      <td className="whitespace-nowrap border border-foreground px-1">
                        {row.name}
                      </td>
                      {day.items.map((i) => (
                        <td key={i.id} className="min-w-16 border border-foreground" />
                      ))}
                    </tr>
                  ))}
                  <tr className="h-8 font-semibold">
                    <td colSpan={3} className="border border-foreground px-1 text-right">
                      {t('print.total')}
                    </td>
                    {day.items.map((i) => (
                      <td key={i.id} className="border border-foreground" />
                    ))}
                  </tr>
                </tbody>
              </table>
              <p className="mt-6 text-right text-sm">{t('print.signature')}</p>
            </section>
          ))}
        </div>
      ) : null}
    </div>
  );
}
