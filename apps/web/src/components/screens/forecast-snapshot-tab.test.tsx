import type { ForecastWeekView } from '@/lib/api-types';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ForecastSnapshotTab } from './forecast-snapshot-tab';

afterEach(() => vi.unstubAllGlobals());

const WEEK = '2026-11-09';

function show(view: ForecastWeekView) {
  const fetchMock = vi.fn(async () => new Response(JSON.stringify(view), { status: 200 }));
  vi.stubGlobal('fetch', fetchMock);
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ForecastSnapshotTab weekStart={WEEK} />
    </QueryClientProvider>,
  );
  return fetchMock;
}

describe('ForecastSnapshotTab (on /admin/registrations)', () => {
  it('BR-07 / BR-44: shows the week snapshot per canteen and day, and its file', async () => {
    const fetchMock = show({
      weekStart: WEEK,
      exportable: true,
      canteens: [
        {
          canteenId: 'ct',
          canteenName: 'Nhà ăn A',
          closedAt: '2026-11-05T16:59:00.000Z',
          days: [
            {
              date: WEEK,
              total: 40,
              meals: [{ displayName: 'Mặn Cơm gà', quantity: 40 }],
            },
          ],
        },
      ],
    });
    expect(await screen.findByText('Nhà ăn A')).toBeInTheDocument();
    expect(screen.getByText('40 × Mặn Cơm gà')).toBeInTheDocument();
    expect(screen.getByText('40 suất')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Tải file dự trù tuần' })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining(`/admin/reconciliation/forecast?weekStart=${WEEK}`),
      expect.anything(),
    );
  });

  it('says when the week is still open and offers no file', async () => {
    show({
      weekStart: WEEK,
      exportable: false,
      canteens: [{ canteenId: 'ct', canteenName: 'Nhà ăn A', closedAt: null, days: [] }],
    });
    expect(await screen.findByText(/Chưa chốt dự trù tuần này/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Tải file dự trù tuần' })).not.toBeInTheDocument();
  });
});
