import type { MyDayView, MyRegistrationWeekView } from '@/lib/api-types';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WeekRegistrationScreen } from './week-registration-screen';

afterEach(() => vi.unstubAllGlobals());

const MAN = { id: 'MAN', name: 'Mặn', color: '#000000' };

const day = (date: string, quantity: number, forecast: number | null): MyDayView => ({
  date,
  window: forecast === null ? 'OPEN' : 'AFTER_CLOSE',
  lockAt: `${date}T01:00:00.000Z`,
  items: [
    {
      menuItemId: `${date}-ga`,
      displayName: '[Mặn] Cơm gà',
      type: MAN,
      quantity,
      forecast: forecast ?? 0,
      replacedAfterClose: null,
    },
  ],
  needsReselect: [],
  desserts: [],
  actualTotal: quantity,
  forecastTotal: forecast,
  changed: forecast !== null && forecast !== quantity,
  extra: false,
});

const week = (forecastTaken: boolean): MyRegistrationWeekView => ({
  state: 'PUBLISHED',
  weekStart: '2026-11-02',
  canteenName: 'Nhà ăn A',
  closeAt: '2026-10-29T17:00:00.000Z',
  forecastTaken,
  days: [
    day('2026-11-02', 2, forecastTaken ? 1 : null),
    day('2026-11-03', 1, forecastTaken ? 1 : null),
  ],
  totalMeals: 3,
});

const show = (view: MyRegistrationWeekView) => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response(JSON.stringify(view), { status: 200 })),
  );
  render(
    <QueryClientProvider client={new QueryClient()}>
      <WeekRegistrationScreen />
    </QueryClientProvider>,
  );
};

describe('WeekRegistrationScreen tabs', () => {
  it('BR-07: before closeAt only "Đăng ký dự trù" exists and holds the steppers', async () => {
    show(week(false));
    expect(await screen.findByRole('tab', { name: 'Đăng ký dự trù' })).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Đăng ký thực tế' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Thêm 1 suất [Mặn] Cơm gà' })).toHaveLength(2);
  });

  it('BR-23: after closeAt "Đăng ký thực tế" opens first, with the forecast and difference per dish', async () => {
    show(week(true));
    expect(
      await screen.findByRole('tab', { name: 'Đăng ký thực tế', selected: true }),
    ).toBeVisible();
    expect(screen.getAllByRole('button', { name: 'Thêm 1 suất [Mặn] Cơm gà' })).toHaveLength(2);
    expect(screen.getByText('(+1)')).toBeInTheDocument();
  });

  it('BR-07: after closeAt "Đăng ký dự trù" is a read-only table of the snapshot', async () => {
    show(week(true));
    await userEvent.click(await screen.findByRole('tab', { name: 'Đăng ký dự trù' }));
    const table = screen.getByRole('table');
    expect(table).toHaveTextContent('Cơm gà');
    expect(screen.getAllByRole('row').at(-1)).toHaveTextContent(/Tổng\s*1\s*1\s*2/);
    expect(screen.queryByRole('button', { name: /Thêm 1 suất/ })).not.toBeInTheDocument();
  });
});
