import type { AdminTodayView, TodayRow } from '@/lib/api-types';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AdminTodayScreen } from './admin-today-screen';

afterEach(() => vi.unstubAllGlobals());

const DATE = '2026-11-03';

const row = (
  employeeCode: string,
  name: string,
  state: TodayRow['presence']['state'],
  cases: TodayRow['cases'],
): TodayRow => ({
  employeeCode,
  name,
  orgPath: 'Xưởng A / Tổ 1',
  canteenId: 'ct',
  lines: [
    { menuItemId: 'ga', displayName: '[Mặn] Cơm gà', quantity: 1, forecast: 1, onMenu: true },
  ],
  forecastTotal: 1,
  actualTotal: 1,
  mealsCount: state !== 'PROVISIONAL_ABSENT',
  presence: {
    state,
    firstCheckInAt: state === 'PRESENT' ? `${DATE}T00:45:00.000Z` : null,
    checkedInInTime: state === 'PRESENT',
    confirmedInDmp: false,
    confirmedByName: null,
    confirmedAt: null,
    confirmReason: null,
  },
  leave: null,
  cases,
});

const view: AdminTodayView = {
  date: DATE,
  now: `${DATE}T01:20:00.000Z`,
  window: 'EXTRA',
  lockAt: `${DATE}T01:00:00.000Z`,
  canConfirmPresence: true,
  closed: null,
  attendance: {
    date: DATE,
    servingDay: true,
    cutoffAt: `${DATE}T01:10:00.000Z`,
    lastAttendanceSync: {
      id: 's1',
      status: 'SUCCESS',
      startedAt: `${DATE}T01:15:00.000Z`,
      finishedAt: `${DATE}T01:15:02.000Z`,
      recordCount: 2,
      error: null,
    },
    lastLeaveSync: null,
    cutoff: {
      runAt: `${DATE}T01:10:00.000Z`,
      outcome: 'CANCELLED',
      withMeals: 2,
      presentWithMeals: 1,
      cancelledCount: 1,
    },
  },
  summary: {
    employees: 2,
    withMeals: 2,
    present: 1,
    provisionallyCancelled: 1,
    forecastMeals: 2,
    actualMeals: 2,
    countedMeals: 1,
    cases: 1,
  },
  menus: [
    {
      canteenId: 'ct',
      canteenName: 'Nhà ăn A',
      items: [
        { id: 'ga', displayName: '[Mặn] Cơm gà', type: { id: 'MAN', name: 'Mặn', color: '#000' } },
      ],
    },
  ],
  rows: [
    row('PNJP000001', 'Nguyễn Văn An', 'PRESENT', []),
    row('PNJP000010', 'Trần Thị Lan', 'PROVISIONAL_ABSENT', ['PROVISIONALLY_CANCELLED']),
  ],
};

function show(canSync = false, initial: { date?: string; q?: string } = {}) {
  const fetchMock = vi.fn(async (url: string) =>
    String(url).includes('/admin/registrations/org-units')
      ? new Response(JSON.stringify({ items: [] }), { status: 200 })
      : String(url).includes('/presence')
        ? new Response(JSON.stringify(view.rows[0]!.presence), { status: 200 })
        : new Response(JSON.stringify(view), { status: 200 }),
  );
  vi.stubGlobal('fetch', fetchMock);
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AdminTodayScreen canSync={canSync} initialDate={initial.date} initialQuery={initial.q} />
    </QueryClientProvider>,
  );
  return fetchMock;
}

describe('AdminTodayScreen', () => {
  it('BR-31 / BR-41: shows presence and lists the provisionally cancelled on the exceptions tab', async () => {
    const user = userEvent.setup();
    show();
    expect(await screen.findByText('Nguyễn Văn An')).toBeInTheDocument();
    expect(screen.getAllByText('Tạm hủy').length).toBeGreaterThan(0);
    expect(screen.getByText(/tạm hủy suất của 1 người/)).toBeInTheDocument();
    // Only the HC admin gets the re-run button.
    expect(screen.queryByRole('button', { name: 'Chạy lại đồng bộ' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Cần xem xét (1)' }));
    expect(screen.queryByText('Nguyễn Văn An')).not.toBeInTheDocument();
    expect(screen.getByText('Trần Thị Lan')).toBeInTheDocument();
  });

  it('does not let the date picker pick a future day', async () => {
    const user = userEvent.setup();
    show();
    await screen.findByText('Nguyễn Văn An');
    await user.click(document.getElementById('today-date')!);
    const dayButton = (iso: string) =>
      screen
        .getAllByRole('gridcell')
        .find((c) => c.getAttribute('data-day') === iso)!
        .querySelector('button')!;
    expect(dayButton('2026-11-03')).toBeEnabled();
    expect(dayButton('2026-11-04')).toBeDisabled();
  });

  it('steps a day back with the arrow, never past today, and jumps back to today', async () => {
    const user = userEvent.setup();
    const fetchMock = show();
    await screen.findByText('Nguyễn Văn An');
    // The fake view is always 2026-11-03, today: no next day, nothing to jump back to.
    expect(screen.getByRole('button', { name: 'Ngày sau' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Về hôm nay' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Ngày trước' }));
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/admin/today?date=2026-11-02'),
        expect.anything(),
      ),
    );
  });

  it('opens on the day and the person a reconciliation case links to', async () => {
    const fetchMock = show(false, { date: '2026-11-03', q: 'PNJP000010' });
    expect(await screen.findByText('Trần Thị Lan')).toBeInTheDocument();
    expect(screen.queryByText('Nguyễn Văn An')).not.toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Tìm theo tên hoặc mã' })).toHaveValue(
      'PNJP000010',
    );
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/admin/today?date=2026-11-03'),
      expect.anything(),
    );
  });

  it('BR-33: confirms presence without a reason', async () => {
    const user = userEvent.setup();
    const fetchMock = show(true);
    expect(await screen.findByRole('button', { name: 'Chạy lại đồng bộ' })).toBeInTheDocument();
    // Present employees need no confirmation: one button, for the provisionally cancelled one.
    const buttons = screen.getAllByRole('button', { name: 'Xác nhận có mặt' });
    expect(buttons).toHaveLength(1);
    await user.click(buttons[0]!);
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận có mặt' }));
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/admin/today/employees/PNJP000010/presence'),
        expect.objectContaining({ method: 'POST' }),
      ),
    );
    const call = fetchMock.mock.calls.find(([u]) => String(u).includes('/presence'))!;
    expect(JSON.parse(String((call as unknown as [string, RequestInit])[1].body))).toEqual({
      date: DATE,
      reason: null,
    });
  });

  it('keeps the list on screen while another unit loads, then shows the new one', async () => {
    const user = userEvent.setup();
    // Touch screens get the native select, which is simplest to drive here.
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query === '(pointer: coarse)',
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    }));
    const units = [
      { id: 'k1', level: 1, levelName: 'Khối', name: 'Khối Sản xuất', parentId: null },
      { id: 'k2', level: 1, levelName: 'Khối', name: 'Khối Văn phòng', parentId: null },
      { id: 'p1', level: 2, levelName: 'Phòng ban', name: 'Phòng Kế toán', parentId: 'k2' },
    ];
    let release: () => void = () => undefined;
    const filtered: AdminTodayView = { ...view, rows: [view.rows[1]!] };
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (String(url).includes('/admin/registrations/org-units')) {
          return new Response(JSON.stringify({ items: units }), { status: 200 });
        }
        if (String(url).includes('orgUnitId=k2')) {
          await new Promise<void>((resolve) => (release = resolve));
          return new Response(JSON.stringify(filtered), { status: 200 });
        }
        return new Response(JSON.stringify(view), { status: 200 });
      }),
    );
    render(
      <QueryClientProvider client={new QueryClient()}>
        <AdminTodayScreen canSync={false} />
      </QueryClientProvider>,
    );
    expect(await screen.findByText('Nguyễn Văn An')).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Khối'), 'k2');
    // The dependent select opens at once; the old list stays (busy), no skeleton in its place.
    expect(await screen.findByLabelText('Phòng ban')).toBeInTheDocument();
    expect(screen.getByText('Nguyễn Văn An')).toBeInTheDocument();
    expect(screen.getByText('Nguyễn Văn An').closest('[aria-busy="true"]')).not.toBeNull();
    expect(screen.queryByText('Đang tải…')).not.toBeInTheDocument();

    release();
    await waitFor(() => expect(screen.queryByText('Nguyễn Văn An')).not.toBeInTheDocument());
    expect(screen.getByText('Trần Thị Lan').closest('[aria-busy]')).toBeNull();
  });

  it('BR-24: saving an edit after the lock needs a reason', async () => {
    const user = userEvent.setup();
    show();
    await screen.findByText('Nguyễn Văn An');
    await user.click(screen.getAllByRole('button', { name: 'Sửa suất' })[0]!);
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('button', { name: 'Lưu' })).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Lý do'), 'Khách');
    expect(within(dialog).getByRole('button', { name: 'Lưu' })).toBeEnabled();
  });
});
