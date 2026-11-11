import type { ReconciliationView, TodayRow } from '@/lib/api-types';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ReconciliationScreen, type ReconciliationPermissions } from './reconciliation-screen';

afterEach(() => vi.unstubAllGlobals());

const DATE = '2026-11-03';

const exception: TodayRow = {
  employeeCode: 'PNJP000010',
  name: 'Trần Thị Lan',
  orgPath: 'Xưởng A / Tổ 1',
  canteenId: 'ct',
  lines: [{ menuItemId: 'ga', displayName: 'Mặn Cơm gà', quantity: 1, forecast: 1, onMenu: true }],
  forecastTotal: 1,
  actualTotal: 1,
  mealsCount: false,
  presence: {
    state: 'PROVISIONAL_ABSENT',
    firstCheckInAt: null,
    checkedInInTime: false,
    confirmedInDmp: false,
    confirmedByName: null,
    confirmedAt: null,
    confirmReason: null,
  },
  leave: null,
  cases: ['PROVISIONALLY_CANCELLED'],
};

const base: ReconciliationView = {
  date: DATE,
  now: `${DATE}T01:35:00.000Z`,
  servingDay: true,
  forecastTaken: true,
  closed: null,
  canFinalize: true,
  finalizeOpensAt: `${DATE}T01:30:00.000Z`,
  autoFinalizeAt: `${DATE}T05:00:00.000Z`,
  adjustmentDeadlineAt: `${DATE}T03:30:00.000Z`,
  canAdjust: false,
  cutoffHeld: false,
  deviationThresholdPct: 10,
  attendance: {
    date: DATE,
    servingDay: true,
    cutoffAt: `${DATE}T01:10:00.000Z`,
    lastAttendanceSync: null,
    lastLeaveSync: null,
    cutoff: null,
  },
  summary: {
    forecast: 40,
    actual: 34,
    difference: -6,
    cases: 1,
    deviatingMeals: 1,
    finalCount: null,
    adjustment: null,
    effective: null,
  },
  meals: [
    {
      menuItemId: 'ga',
      displayName: 'Mặn Cơm gà',
      canteenId: 'ct',
      canteenName: 'Nhà ăn A',
      forecast: 40,
      actual: 34,
      difference: -6,
      status: 'MISMATCHED',
      deviates: true,
      deviationPct: -15,
      finalCount: null,
      adjustment: null,
      effective: null,
    },
  ],
  exceptions: [exception],
};

const HC: ReconciliationPermissions = { finalize: true, export: true, adjust: true, sync: true };
const DEPT: ReconciliationPermissions = {
  finalize: false,
  export: false,
  adjust: false,
  sync: false,
};

function show(view: ReconciliationView, can: ReconciliationPermissions) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const u = String(url);
    if (u.includes('/admin/registrations/org-units')) {
      return new Response(JSON.stringify({ items: [] }), { status: 200 });
    }
    if (u.includes('/finalize') && init?.method === 'POST') {
      return new Response(
        JSON.stringify({
          date: DATE,
          finalizedAt: view.now,
          autoFinalized: false,
          cancelled: 1,
          employees: 33,
          meals: 34,
          cutoffOutcome: 'CANCELLED',
        }),
        { status: 200 },
      );
    }
    if (u.includes('/admin/adjustments')) {
      return new Response(
        JSON.stringify({
          date: DATE,
          closed: view.closed,
          canAdjust: true,
          adjustmentDeadlineAt: view.adjustmentDeadlineAt,
          items: [],
        }),
        { status: 200 },
      );
    }
    return new Response(JSON.stringify(view), { status: 200 });
  });
  vi.stubGlobal('fetch', fetchMock);
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ReconciliationScreen can={can} />
    </QueryClientProvider>,
  );
  return fetchMock;
}

describe('ReconciliationScreen', () => {
  it('BR-40 / BR-42: shows the summary, the per-meal comparison with its warning, and the cases', async () => {
    const user = userEvent.setup();
    show(base, DEPT);
    expect(await screen.findByText('Mặn Cơm gà')).toBeInTheDocument();
    expect(screen.getByText('Lệch')).toBeInTheDocument();
    expect(screen.getByText('Lệch -15% (ngưỡng 10%)')).toBeInTheDocument();
    // A dept admin views only: no finalize button, no file.
    expect(screen.queryByRole('button', { name: 'Chốt số' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Cần xem xét (1)' }));
    expect(screen.getByText('Trần Thị Lan')).toBeInTheDocument();
    expect(screen.getByText('Tạm hủy do chưa chấm công')).toBeInTheDocument();
    // Cases are handled on /admin/today: each row opens that page on the day, searching the person.
    expect(screen.getByRole('link', { name: 'Mở Trần Thị Lan ở trang Hôm nay' })).toHaveAttribute(
      'href',
      '/admin/today?date=2026-11-03&q=PNJP000010',
    );
  });

  it('works one day at a time: no weekly forecast tab, a one-line sync, no day after today', async () => {
    show(base, HC);
    await screen.findByText('Mặn Cơm gà');
    // The weekly forecast lives on /admin/registrations now.
    expect(screen.queryByRole('tab', { name: /Dự trù/ })).not.toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Đồng bộ chấm công' })).toHaveTextContent(
      'Chấm công: Chưa đồng bộ lần nào trong ngày. · Chốt chấm công lúc 08:10: chưa chạy.',
    );
    expect(screen.getByRole('button', { name: 'Ngày sau' })).toBeDisabled();
    expect(screen.queryByText(/Đang xem ngày đã qua/)).not.toBeInTheDocument();
  });

  it('says a past day is for looking back only', async () => {
    show({ ...base, now: '2026-11-05T02:00:00.000Z' }, HC);
    expect(await screen.findByText(/Đang xem ngày đã qua/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ngày sau' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Về hôm nay' })).toBeEnabled();
  });

  it('does not let the date picker pick a future day', async () => {
    const user = userEvent.setup();
    show(base, DEPT);
    await screen.findByText('Mặn Cơm gà');
    await user.click(document.getElementById('recon-date')!);
    const dayButton = (iso: string) =>
      screen
        .getAllByRole('gridcell')
        .find((c) => c.getAttribute('data-day') === iso)!
        .querySelector('button')!;
    expect(dayButton('2026-11-03')).toBeEnabled();
    expect(dayButton('2026-11-04')).toBeDisabled();
  });

  it('BR-43: the HC admin finalizes after confirming', async () => {
    const user = userEvent.setup();
    const fetchMock = show(base, HC);
    await user.click(await screen.findByRole('button', { name: 'Chốt số' }));
    const dialog = await screen.findByRole('alertdialog');
    await user.click(within(dialog).getByRole('button', { name: 'Chốt số' }));
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/admin/reconciliation/finalize'),
        expect.objectContaining({ method: 'POST', body: JSON.stringify({ date: DATE }) }),
      ),
    );
  });

  it('BR-43 / BR-46: a finalized day shows who closed it, the effective count and the adjustments tab', async () => {
    const closed: ReconciliationView = {
      ...base,
      closed: {
        finalizedAt: `${DATE}T01:31:00.000Z`,
        finalizedByName: 'Lê Cường',
        autoFinalized: false,
      },
      canFinalize: false,
      canAdjust: true,
      summary: { ...base.summary, finalCount: 34, adjustment: 1, effective: 35 },
    };
    show(closed, HC);
    expect(await screen.findByText(/Đã chốt lúc 08:31 bởi Lê Cường/)).toBeInTheDocument();
    expect(screen.getByText('35')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Tải file chốt số' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Chốt số' })).not.toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Điều chỉnh' })).toBeInTheDocument();
  });
});
