'use client';

import type { TeamDayView } from '@/lib/api-types';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Pagination } from '@/components/ui/pagination';
import { LoadingRows } from '@/components/ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { useDebounced } from '@/hooks/use-debounced';
import { usePagination } from '@/hooks/use-pagination';
import { ApiError, api } from '@/lib/api';
import { apiErrorText } from '@/lib/errors';
import { formatDate } from '@/lib/utils';
import { t } from '@/messages';

/**
 * /team (BR-27): colleagues of the caller's meal unit today, with name, meal, quantity and
 * eating / not eating only. Never a reason for absence or leave.
 */
export function TeamScreen() {
  const [q, setQ] = useState('');
  const term = useDebounced(q.trim());
  const query = useQuery({
    queryKey: ['/team', term],
    queryFn: () => api.get<TeamDayView>(`/team${term ? `?q=${encodeURIComponent(term)}` : ''}`),
    placeholderData: (prev) => prev,
  });
  const data = query.data;
  const eating = data?.members.filter((m) => m.eating).length ?? 0;
  const meals =
    data?.members.reduce((s, m) => s + m.meals.reduce((x, y) => x + y.quantity, 0), 0) ?? 0;
  const { pageItems, pagination } = usePagination(data?.members ?? [], term);

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('team.title')}</CardTitle>
        <CardDescription>{t('team.intro')}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        <div className="flex w-full flex-col gap-2 sm:w-72">
          <Label htmlFor="team-q">{t('common.search')}</Label>
          <Input id="team-q" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        {query.error ? (
          // No meal unit means no team (BR-60): the API answers 403 or 404.
          query.error instanceof ApiError && [403, 404].includes(query.error.status) ? (
            <Alert variant="warning">{t('week.unassigned')}</Alert>
          ) : (
            <Alert variant="destructive">{apiErrorText(query.error)}</Alert>
          )
        ) : null}
        {query.isLoading ? <LoadingRows /> : null}
        {data ? (
          <>
            <p className="text-sm">
              {t('team.unit', { unit: data.unitName, date: formatDate(data.date) })} ·{' '}
              {t('team.summary', { eating, total: data.members.length, meals })}
            </p>
            {data.members.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t('team.empty')}</p>
            ) : (
              <div className="flex flex-col gap-3">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t('common.name')}</TableHead>
                      <TableHead>{t('team.meals')}</TableHead>
                      <TableHead>{t('team.status')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {pageItems.map((m) => (
                      <TableRow key={m.key}>
                        <TableCell className="font-medium">{m.name}</TableCell>
                        <TableCell>
                          {m.meals.length === 0
                            ? t('common.none')
                            : m.meals
                                .map((meal) => `${meal.quantity} × ${meal.displayName}`)
                                .join(', ')}
                        </TableCell>
                        <TableCell>
                          <Badge variant={m.eating ? 'success' : 'outline'}>
                            {m.eating ? t('team.eating') : t('team.notEating')}
                          </Badge>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
                <Pagination {...pagination} />
              </div>
            )}
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}
