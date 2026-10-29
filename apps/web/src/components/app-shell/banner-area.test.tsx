import type { BannerView } from '@/lib/api-types';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BannerArea } from './banner-area';

let pathname = '/week';
vi.mock('next/navigation', () => ({ usePathname: () => pathname }));

afterEach(() => {
  vi.unstubAllGlobals();
  pathname = '/week';
});

const menuWarning = (severity: BannerView['severity'] = 'warning'): BannerView => ({
  id: `menu-not-published:${severity}`,
  severity,
  kind: 'MENU_NOT_PUBLISHED',
  params: { canteen: 'Nhà ăn A', weekStart: '2026-11-02' },
});

const replaced: BannerView = {
  id: 'notification:n1',
  severity: 'info',
  kind: 'NOTIFICATION',
  notificationId: 'n1',
  notificationType: 'MENU_DISH_REPLACED',
  params: { date: '2026-11-02', from: '[Mặn] Cá kho', to: '[Mặn] Sườn ram' },
};

const removed: BannerView = {
  id: 'notification:n2',
  severity: 'info',
  kind: 'NOTIFICATION',
  notificationId: 'n2',
  notificationType: 'MENU_DISH_REMOVED',
  params: { date: '2026-11-03', dish: '[Chay] Đậu sốt', lockTime: '08:00' },
};

/** GET /me/banners answers `items`; POSTs (mark read) answer ok. */
const stubApi = (items: BannerView[]) => {
  const fetch = vi.fn(
    async (_url: string, _init?: RequestInit) =>
      new Response(JSON.stringify({ items }), { status: 200 }),
  );
  vi.stubGlobal('fetch', fetch);
  return fetch;
};

const show = (initial: BannerView[]) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const ui = () => (
    <QueryClientProvider client={client}>
      <BannerArea userId="u1" initial={initial} />
    </QueryClientProvider>
  );
  const result = render(ui());
  return { ...result, rerender: () => result.rerender(ui()) };
};

describe('BannerArea', () => {
  it('renders nothing when there are no banners', () => {
    stubApi([]);
    const { container } = show([]);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders warnings as status and errors as alerts', () => {
    stubApi([]);
    show([menuWarning('warning'), menuWarning('error')]);
    expect(screen.getByRole('status')).toHaveTextContent('chưa được công bố');
    expect(screen.getByRole('alert')).toHaveTextContent('chưa được công bố');
  });

  it('BR-06: links the unpublished-menu warning to the menu builder, except on that screen', () => {
    stubApi([]);
    pathname = '/admin/org';
    const { unmount } = show([menuWarning()]);
    expect(screen.getByRole('link', { name: 'Mở Thực đơn tuần' })).toHaveAttribute(
      'href',
      '/admin/menu',
    );
    unmount();
    pathname = '/admin/menu';
    show([menuWarning()]);
    expect(screen.queryByRole('link', { name: 'Mở Thực đơn tuần' })).toBeNull();
  });

  it('BR-14: a notice written while the page is open shows on the next page, without a reload', async () => {
    const fetch = stubApi([replaced]);
    const { rerender } = show([]);
    expect(screen.queryByRole('status')).toBeNull();
    // The server just rendered the first list, so mounting does not fetch it again.
    expect(fetch).not.toHaveBeenCalled();
    pathname = '/today';
    rerender();
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
    // Back to the first page: it may have a new notice by now too.
    pathname = '/week';
    rerender();
    expect(await screen.findByRole('status')).toHaveTextContent(
      'món [Mặn] Cá kho được đổi thành [Mặn] Sườn ram',
    );
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
    expect(fetch.mock.calls[0]![0]).toBe('/api/me/banners');
  });

  it('BR-14: several notices fold into one line that opens into a list', async () => {
    stubApi([replaced, removed]);
    pathname = '/today';
    show([replaced, removed]);
    const box = screen.getByRole('status');
    expect(box).toHaveTextContent('Bạn có 2 thông báo mới về thay đổi thực đơn.');
    expect(screen.queryByText(/được đổi thành/)).not.toBeVisible();
    // The removed dish still needs reselecting, so the summary keeps the shortcut.
    expect(screen.getByRole('link', { name: 'Chọn lại món' })).toHaveAttribute('href', '/week');

    await userEvent.click(screen.getByRole('button', { name: 'Xem chi tiết' }));
    const items = screen.getAllByRole('listitem');
    // The notice that needs something from the employee comes first.
    expect(items[0]).toHaveTextContent('đã bị bỏ');
    expect(items[1]).toHaveTextContent('được đổi thành');
    expect(screen.getByRole('button', { name: 'Thu gọn' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
  });

  it('BR-14: "Đã xem tất cả" marks every notice read and hides the box', async () => {
    const fetch = stubApi([]);
    show([replaced, removed]);
    await userEvent.click(screen.getByRole('button', { name: 'Đã xem tất cả' }));
    await waitFor(() => expect(screen.queryByRole('status')).toBeNull());
    const posts = fetch.mock.calls
      .filter(([, init]) => init?.method === 'POST')
      .map(([url]) => url)
      .sort();
    expect(posts).toEqual(['/api/me/notifications/n1/read', '/api/me/notifications/n2/read']);
  });
});
