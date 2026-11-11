'use client';

import { Download } from 'lucide-react';
import type { ForecastWeekView } from '@/lib/api-types';
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { LoadingRows, Refreshing } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableRow } from '@/components/ui/table';
import { api } from '@/lib/api';
import { apiErrorText } from '@/lib/errors';
import { formatDate, formatVnTime } from '@/lib/utils';
import { t } from '@/messages';
import { weekdayName } from './day-meals';

/**
 * BR-07 / BR-44: the forecast snapshot of a week, per canteen and day, and its Excel file for the
 * caterer. It lives on /admin/registrations, next to the progress of the same week: the forecast is
 * a weekly figure taken at closeAt, while /admin/reconciliation works one day at a time
 * (docs/decisions.md). The API paths stay under /admin/reconciliation/forecast.
 */
export function ForecastSnapshotTab({ weekStart }: { weekStart: string }) {
  const query = useQuery({
    queryKey: ['/admin/reconciliation/forecast', weekStart],
    queryFn: () =>
      api.get<ForecastWeekView>(`/admin/reconciliation/forecast?weekStart=${weekStart}`),
    placeholderData: keepPreviousData,
  });
  const download = useMutation({
    mutationFn: () =>
      api.download(
        `/admin/reconciliation/forecast/export?weekStart=${weekStart}`,
        `du-tru-tuan-${weekStart}.xlsx`,
      ),
  });
  const view = query.data;
  return (
    <div className="flex flex-col gap-4">
      {query.error ? <Alert variant="destructive">{apiErrorText(query.error)}</Alert> : null}
      {download.error ? <Alert variant="destructive">{apiErrorText(download.error)}</Alert> : null}
      {query.isLoading ? <LoadingRows rows={3} /> : null}
      {view ? (
        <Refreshing busy={query.isPlaceholderData} className="flex flex-col gap-4">
          {view.exportable ? (
            <div>
              <Button
                variant="outline"
                disabled={download.isPending}
                onClick={() => download.mutate()}
              >
                <Download className="h-4 w-4" aria-hidden />
                {t('registrations.forecast.export')}
              </Button>
            </div>
          ) : null}
          {view.canteens.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t('registrations.forecast.none')}</p>
          ) : null}
          {view.canteens.map((c) => (
            <section key={c.canteenId} className="flex flex-col gap-2">
              <h3 className="font-medium">{c.canteenName}</h3>
              {c.closedAt === null ? (
                <p className="text-sm text-muted-foreground">
                  {t('registrations.forecast.notTaken')}
                </p>
              ) : (
                <>
                  <p className="text-xs text-muted-foreground">
                    {t('registrations.forecast.takenAt', { at: formatVnTime(c.closedAt) })}
                  </p>
                  <Table>
                    <TableBody>
                      {c.days.map((d) => (
                        <TableRow key={d.date}>
                          <TableCell className="whitespace-nowrap">
                            {weekdayName(d.date)} {formatDate(d.date)}
                          </TableCell>
                          <TableCell className="text-sm">
                            {d.meals.map((m) => `${m.quantity} × ${m.displayName}`).join(', ') ||
                              t('common.none')}
                          </TableCell>
                          <TableCell className="text-right font-medium tabular-nums">
                            {t('registrations.forecast.total', { count: d.total })}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </>
              )}
            </section>
          ))}
        </Refreshing>
      ) : null}
    </div>
  );
}
