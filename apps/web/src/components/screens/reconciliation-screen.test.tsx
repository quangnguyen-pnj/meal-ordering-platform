import type { ReconciliationView, TodayRow } from '@/lib/api-types';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TooltipProvider } from '@/components/ui/tooltip';
import {
  ReconciliationScreen,
  signed,
  signedPct,
  type ReconciliationPermissions,
} from './reconciliation-screen';

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

function show(
  view: ReconciliationView,
  can: ReconciliationPermissions,
  extra: (url: string, init?: RequestInit) => Response | undefined = () => undefined,
) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const u = String(url);
    const custom = extra(u, init);
    if (custom) return custom;
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
      <TooltipProvider>
        <ReconciliationScreen can={can} />
      </TooltipProvider>
    </QueryClientProvider>,
  );
  return fetchMock;
}

describe('ReconciliationScreen', () => {
  it('BR-40 / BR-42: shows the summary, the per-meal comparison with its warning, and the cases', async () => {
    const user = userEvent.setup();
    show(base, DEPT);
    expect(await screen.findByText('Mặn Cơm gà')).toBeInTheDocument();
    // One badge per meal; the threshold is said once, above the table.
    expect(screen.getByText('Vượt ngưỡng −15%')).toBeInTheDocument();
    expect(screen.queryByText('Lệch')).not.toBeInTheDocument();
    expect(screen.getByText(/lệch quá 10% so với dự trù/)).toBeInTheDocument();
    expect(screen.getByText('−6 so với dự trù (−15%)')).toBeInTheDocument();
    // A dept admin views only: no finalize button, no file.
    expect(screen.queryByRole('button', { name: 'Chốt số' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Cần xem xét (1)' }));
    expect(screen.getByText('Trần Thị Lan')).toBeInTheDocument();
    expect(screen.getByText('Tạm hủy do chưa chấm công')).toBeInTheDocument();
    // Forecast → actual in one cell: the provisionally cancelled meal does not count.
    expect(screen.getByText('1 × Mặn Cơm gà')).toBeInTheDocument();
    expect(screen.getByText('Không tính suất')).toBeInTheDocument();
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
    // After finalizing: "Thực tế" is the final count, "Đã chốt" is that plus the adjustments.
    const tile = (label: string) => screen.getByText(label, { selector: 'dt' }).closest('div')!;
    expect(tile('Thực tế')).toHaveTextContent('34');
    expect(tile('Đã chốt')).toHaveTextContent('35');
    expect(screen.queryByText('Hiệu lực')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Tải file chốt số' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Chốt số' })).not.toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Điều chỉnh' })).toBeInTheDocument();
  });

  it('groups the meals by canteen, with a subtotal per canteen and the day total', async () => {
    const meal = base.meals[0]!;
    show(
      {
        ...base,
        meals: [
          meal,
          {
            ...meal,
            menuItemId: 'pho',
            displayName: 'Phở bò',
            forecast: 10,
            actual: 12,
            difference: 2,
            deviates: true,
            deviationPct: 20,
          },
          {
            ...meal,
            menuItemId: 'bun',
            displayName: 'Bún riêu',
            canteenId: 'cb',
            canteenName: 'Nhà ăn B',
            forecast: 5,
            actual: 5,
            difference: 0,
            status: 'MATCHED',
            deviates: false,
            deviationPct: 0,
          },
        ],
      },
      DEPT,
    );
    await screen.findByText('Phở bò');
    const row = (name: string) =>
      screen.getAllByRole('row').find((r) => within(r).queryByText(name))!;
    expect(row('Nhà ăn A')).toHaveTextContent('Nhà ăn A5046−4');
    expect(row('Nhà ăn B')).toHaveTextContent('Nhà ăn B55—');
    expect(row('Tổng')).toHaveTextContent('Tổng5551−4');
    expect(within(row('Bún riêu')).getByText('Khớp')).toBeInTheDocument();
  });

  it('BR-41: shows the most urgent cases first, handled ones in grey, and filters by case', async () => {
    const user = userEvent.setup();
    const person = (code: string, name: string, cases: TodayRow['cases']): TodayRow => ({
      ...exception,
      employeeCode: code,
      name,
      mealsCount: true,
      cases,
    });
    show(
      {
        ...base,
        exceptions: [
          person('P1', 'Người Đã Xác Nhận', ['PRESENCE_CONFIRMED_BY_ADMIN']),
          person('P2', 'Người Đổi Món', ['DISH_CHANGED']),
          person('P3', 'Người Chấm Công Muộn', ['LATE_CHECK_IN']),
        ],
      },
      DEPT,
    );
    await user.click(await screen.findByRole('tab', { name: 'Cần xem xét (3)' }));
    const names = () =>
      screen
        .getAllByRole('row')
        .slice(1)
        .map((r) => r.querySelector('td span')?.textContent);
    expect(names()).toEqual(['Người Chấm Công Muộn', 'Người Đổi Món', 'Người Đã Xác Nhận']);
    expect(screen.getByText('Chấm công về sau khi chốt').className).toContain('text-destructive');
    expect(screen.getByText('Quản trị xác nhận có mặt').className).toContain('text-muted');

    await user.click(screen.getByRole('button', { name: 'Đổi món (1)' }));
    expect(names()).toEqual(['Người Đổi Món']);
    await user.click(screen.getByRole('button', { name: 'Tất cả (3)' }));
    expect(names()).toHaveLength(3);
  });

  it('BR-34: a held cutoff is said before finalizing, not only in the confirm dialog', async () => {
    show(
      {
        ...base,
        cutoffHeld: true,
        attendance: {
          ...base.attendance,
          cutoff: {
            outcome: 'HELD_SYNC_FAILED',
            runAt: `${DATE}T01:10:00.000Z`,
            cancelledCount: 0,
            presentWithMeals: 0,
            withMeals: 30,
          },
        },
      },
      HC,
    );
    expect(await screen.findByText(/Mốc 08:10 đang được giữ/)).toBeInTheDocument();
    // No BR code in the copy people read.
    expect(screen.getByRole('region', { name: 'Đồng bộ chấm công' })).not.toHaveTextContent(
      'BR-34',
    );
  });

  it('BR-32: offers the attendance re-run only until the adjustment deadline of a finalized day', async () => {
    const closed: ReconciliationView = {
      ...base,
      closed: {
        finalizedAt: `${DATE}T01:31:00.000Z`,
        finalizedByName: 'Lê Cường',
        autoFinalized: false,
      },
      canFinalize: false,
      canAdjust: true,
      summary: { ...base.summary, finalCount: 34, adjustment: 0, effective: 34 },
    };
    show(closed, HC);
    expect(await screen.findByRole('button', { name: 'Chạy lại đồng bộ' })).toBeInTheDocument();
  });

  it('BR-32: no attendance re-run once the adjustment deadline has passed', async () => {
    show(
      {
        ...base,
        now: `${DATE}T04:00:00.000Z`,
        closed: {
          finalizedAt: `${DATE}T01:31:00.000Z`,
          finalizedByName: null,
          autoFinalized: true,
        },
        canFinalize: false,
        canAdjust: false,
        summary: { ...base.summary, finalCount: 34, adjustment: 0, effective: 34 },
      },
      HC,
    );
    expect(await screen.findByText(/Đã quá hạn điều chỉnh/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Chạy lại đồng bộ' })).not.toBeInTheDocument();
  });

  it('BR-46: the dialog sends new − current per meal and asks the caterer only when adding', async () => {
    const user = userEvent.setup();
    const closed: ReconciliationView = {
      ...base,
      closed: {
        finalizedAt: `${DATE}T01:31:00.000Z`,
        finalizedByName: 'Lê Cường',
        autoFinalized: false,
      },
      canFinalize: false,
      canAdjust: true,
      summary: { ...base.summary, finalCount: 34, adjustment: 0, effective: 34 },
    };
    const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200 });
    const fetchMock = show(closed, HC, (u, init) => {
      if (u.includes('/employees?')) {
        return json({
          items: [
            { id: 'e1', employeeCode: 'PNJP000010', name: 'Trần Thị Lan', orgPath: 'Xưởng A' },
          ],
          total: 1,
        });
      }
      if (u.includes('/admin/adjustments/employees/')) {
        return json({
          employeeCode: 'PNJP000010',
          name: 'Trần Thị Lan',
          orgPath: 'Xưởng A',
          canteenName: 'Nhà ăn A',
          meals: [
            {
              menuItemId: 'ga',
              displayName: 'Mặn Cơm gà',
              effective: 1,
              finalQuantity: 1,
              adjusted: false,
              onMenu: true,
            },
            {
              menuItemId: 'pho',
              displayName: 'Phở bò',
              effective: 0,
              finalQuantity: 0,
              adjusted: false,
              onMenu: true,
            },
          ],
        });
      }
      if (u.endsWith('/admin/adjustments') && init?.method === 'POST') {
        return json({ id: 'a1', number: 1 });
      }
      return undefined;
    });
    await user.click(await screen.findByRole('tab', { name: 'Điều chỉnh' }));
    await user.click(await screen.findByRole('button', { name: 'Thêm điều chỉnh' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(document.getElementById('adjustment-employee')!);
    await user.keyboard('Trần');
    await user.click(await screen.findByRole('option', { name: /Trần Thị Lan/ }));

    // Removing a meal needs no caterer confirmation.
    await user.click(await within(dialog).findByRole('button', { name: 'Bớt 1 suất Mặn Cơm gà' }));
    expect(within(dialog).getByText('Hiện có 1 → 0 (−1)')).toBeInTheDocument();
    expect(within(dialog).queryByLabelText(/Nhà cung cấp đã đồng ý/)).not.toBeInTheDocument();

    // Adding one does.
    await user.click(within(dialog).getByRole('button', { name: 'Thêm 1 suất Phở bò' }));
    expect(within(dialog).getByLabelText(/Nhà cung cấp đã đồng ý/)).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Lý do'), 'Đổi món cho khách');
    const save = within(dialog).getByRole('button', { name: 'Lưu điều chỉnh' });
    expect(save).toBeDisabled();
    await user.click(within(dialog).getByLabelText(/Nhà cung cấp đã đồng ý/));
    await user.click(save);
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringMatching(/\/admin\/adjustments$/),
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({
            date: DATE,
            reason: 'Đổi món cho khách',
            catererConfirmed: true,
            lines: [
              { menuItemId: 'ga', delta: -1, employeeCode: 'PNJP000010' },
              { menuItemId: 'pho', delta: 1, employeeCode: 'PNJP000010' },
            ],
          }),
        }),
      ),
    );
  });
});

describe('number formatting', () => {
  it('uses a real minus sign and a Vietnamese decimal comma', () => {
    expect(signed(-19)).toBe('−19');
    expect(signed(3)).toBe('+3');
    expect(signed(0)).toBe('0');
    expect(signedPct(-10.56)).toBe('−10,6');
    expect(signedPct(20)).toBe('+20');
  });
});
