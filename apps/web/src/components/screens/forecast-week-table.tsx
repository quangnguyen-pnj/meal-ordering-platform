'use client';

import type { MyDayView } from '@/lib/api-types';
import { DishName } from '@/components/dish-name';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { forecastGrid } from '@/lib/registration-draft';
import { formatDate } from '@/lib/utils';
import { t } from '@/messages';
import { weekdayName } from './day-meals';

const cell = (n: number | undefined) => (n ? n : '–');

/**
 * /week, "Dự trù tuần" tab once the forecast is taken (BR-07): the snapshot as one read-only
 * table, dishes by day, so the employee reviews the whole week at a glance.
 */
export function ForecastWeekTable({ days }: { days: readonly MyDayView[] }) {
  const grid = forecastGrid(days);
  if (grid.total === 0) {
    return <p className="text-sm text-muted-foreground">{t('week.forecastTable.empty')}</p>;
  }
  const hasOther = Object.keys(grid.other).length > 0;
  return (
    <Table className="min-w-[36rem]">
      <TableHeader>
        <TableRow>
          <TableHead>{t('week.forecastTable.dish')}</TableHead>
          {days.map((d) => (
            <TableHead key={d.date} className="text-center">
              {weekdayName(d.date)}
              <span className="block font-normal normal-case tracking-normal">
                {formatDate(d.date).slice(0, 5)}
              </span>
            </TableHead>
          ))}
          <TableHead className="text-center">{t('week.forecastTable.total')}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {grid.rows.map((row) => (
          <TableRow key={row.displayName}>
            <TableCell>
              <DishName type={row.type} displayName={row.displayName} />
            </TableCell>
            {days.map((d) => (
              <TableCell key={d.date} className="text-center tabular-nums">
                {cell(row.byDate[d.date])}
              </TableCell>
            ))}
            <TableCell className="text-center font-medium tabular-nums">{row.total}</TableCell>
          </TableRow>
        ))}
        {hasOther ? (
          <TableRow>
            <TableCell className="text-muted-foreground">{t('week.forecastTable.other')}</TableCell>
            {days.map((d) => (
              <TableCell key={d.date} className="text-center tabular-nums">
                {cell(grid.other[d.date])}
              </TableCell>
            ))}
            <TableCell className="text-center font-medium tabular-nums">
              {Object.values(grid.other).reduce((s, n) => s + n, 0)}
            </TableCell>
          </TableRow>
        ) : null}
        <TableRow className="bg-muted/50 font-medium hover:bg-muted/50">
          <TableCell>{t('week.forecastTable.total')}</TableCell>
          {days.map((d) => (
            <TableCell key={d.date} className="text-center tabular-nums">
              {grid.dayTotals[d.date]}
            </TableCell>
          ))}
          <TableCell className="text-center tabular-nums">{grid.total}</TableCell>
        </TableRow>
      </TableBody>
    </Table>
  );
}
