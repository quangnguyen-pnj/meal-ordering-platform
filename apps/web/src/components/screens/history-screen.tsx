'use client';

import { addDays } from '@meal/shared';
import type { HistoryView } from '@/lib/api-types';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { LoadingRows } from '@/components/ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { api } from '@/lib/api';
import { apiErrorText } from '@/lib/errors';
import { formatDate } from '@/lib/utils';
import { t } from '@/messages';
import { weekdayName } from './day-meals';

const STATUS_VARIANT = {
  REGISTERED: 'success',
  NOT_REGISTERED: 'outline',
  CANCELLED: 'destructive',
} as const;

/** /history: past days with the forecast taken at closeAt, the actual meals and a status. */
export function HistoryScreen() {
  // Null: the API picks the last 4 weeks up to today (server time).
  const [range, setRange] = useState<{ from: string; to: string } | null>(null);
  const query = useQuery({
    queryKey: ['/registrations/me/history', range],
    queryFn: () =>
      api.get<HistoryView>(
        `/registrations/me/history${range ? `?from=${range.from}&to=${range.to}` : ''}`,
      ),
  });
  const data = query.data;
  const shift = (days: number) =>
    data && setRange({ from: addDays(data.from, days), to: addDays(data.to, days) });

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('history.title')}</CardTitle>
        <CardDescription>{t('history.intro')}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        {query.error ? <Alert variant="destructive">{apiErrorText(query.error)}</Alert> : null}
        {data ? (
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="outline" size="sm" onClick={() => shift(-28)}>
              ‹ {t('history.earlier')}
            </Button>
            <span className="text-sm font-medium">
              {t('history.range', { from: formatDate(data.from), to: formatDate(data.to) })}
            </span>
            <Button variant="outline" size="sm" disabled={range === null} onClick={() => shift(28)}>
              {t('history.later')} ›
            </Button>
          </div>
        ) : null}
        {query.isLoading ? <LoadingRows /> : null}
        {data && data.items.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('history.empty')}</p>
        ) : null}
        {data && data.items.length > 0 ? (
          <Table className="min-w-[36rem]">
            <TableHeader>
              <TableRow>
                <TableHead>{t('history.date')}</TableHead>
                <TableHead className="text-right">{t('history.forecast')}</TableHead>
                <TableHead className="text-right">{t('history.actual')}</TableHead>
                <TableHead>{t('history.meals')}</TableHead>
                <TableHead>{t('history.statusLabel')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.items.map((d) => (
                <TableRow key={d.date}>
                  <TableCell className="whitespace-nowrap">
                    {weekdayName(d.date)} {formatDate(d.date)}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">
                    {d.forecast ?? t('common.none')}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{d.actual}</TableCell>
                  <TableCell className="text-sm">
                    {d.meals
                      .map(
                        (m) =>
                          `${m.actual} × ${m.displayName}` +
                          (m.cancelled > 0
                            ? ` (${t('history.cancelled', { count: m.cancelled })})`
                            : ''),
                      )
                      .join(', ') || t('common.none')}
                  </TableCell>
                  <TableCell>
                    <Badge variant={STATUS_VARIANT[d.status]}>
                      {t(`history.status.${d.status}`)}
                    </Badge>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ) : null}
      </CardContent>
    </Card>
  );
}
