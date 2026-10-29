'use client';

import type { BannerView } from '@/lib/api-types';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bell, ChevronDown, ChevronUp } from 'lucide-react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useEffect, useId, useRef, useState } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { api } from '@/lib/api';
import { BANNERS_REFRESH_MS, bannersKey, toBanners } from '@/lib/banners';
import { cn } from '@/lib/utils';
import { t } from '@/messages';

export interface Banner {
  id: string;
  severity: 'info' | 'warning' | 'error';
  message: string;
  /** In-app notice (BR-14) the user can mark as read. */
  dismissId?: string;
  /** Where to fix it, e.g. the menu builder for "menu not published". */
  action?: { href: string; label: string };
}

const VARIANT = { info: 'default', warning: 'warning', error: 'destructive' } as const;

/**
 * Top-of-page warnings shared by every screen (plan section 4): menu not published (BR-06) and
 * in-app notices (BR-14); attendance sync and finalization warnings join in P3 and P4.
 * The layout renders the first list on the server. The shared layout is not rendered again on
 * client navigation, so the list is refetched here on every page change, on focus and every
 * minute: a notice written while the page is open shows up without a reload.
 */
export function BannerArea({
  userId,
  initial,
}: {
  userId: string;
  initial: readonly BannerView[];
}) {
  const qc = useQueryClient();
  const pathname = usePathname();
  const key = bannersKey(userId);
  const query = useQuery({
    queryKey: key,
    queryFn: () => api.get<{ items: BannerView[] }>('/me/banners').then((r) => r.items),
    initialData: [...initial],
    // The server just fetched the first list; do not fetch it again on mount.
    staleTime: BANNERS_REFRESH_MS / 2,
    refetchInterval: BANNERS_REFRESH_MS,
    refetchOnWindowFocus: true,
  });
  const { refetch } = query;
  const lastPath = useRef(pathname);
  useEffect(() => {
    if (pathname === lastPath.current) return;
    lastPath.current = pathname;
    void refetch();
  }, [pathname, refetch]);

  const dismiss = useMutation({
    mutationFn: (ids: readonly string[]) =>
      Promise.all(ids.map((id) => api.post(`/me/notifications/${id}/read`))),
    onMutate: (ids) =>
      qc.setQueryData<BannerView[]>(key, (prev) =>
        prev?.filter((b) => !b.notificationId || !ids.includes(b.notificationId)),
      ),
    // A failed call brings the notice back.
    onSettled: () => qc.invalidateQueries({ queryKey: key }),
  });

  const banners = toBanners(query.data);
  const system = banners.filter((b) => !b.dismissId);
  // Notices that need something from the employee (reselect a dish) come first.
  const notices = banners
    .filter((b) => b.dismissId)
    .sort((a, b) => Number(Boolean(b.action)) - Number(Boolean(a.action)));
  if (banners.length === 0) return null;

  return (
    <section aria-label={t('banners.label')} className="flex flex-col gap-2">
      {system.map((b) => (
        <Alert
          key={b.id}
          variant={VARIANT[b.severity]}
          role={b.severity === 'error' ? 'alert' : 'status'}
          className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between sm:gap-3"
        >
          <span>{b.message}</span>
          <BannerActions banner={b} />
        </Alert>
      ))}
      {notices.length > 0 ? (
        <NoticeBox notices={notices} onDismiss={(ids) => dismiss.mutate(ids)} />
      ) : null}
    </section>
  );
}

function BannerActions({ banner, onDismiss }: { banner: Banner; onDismiss?: () => void }) {
  const pathname = usePathname();
  return (
    <div className="flex shrink-0 gap-1">
      {banner.action && !pathname.startsWith(banner.action.href) ? (
        <Button asChild variant="outline" size="sm">
          <Link href={banner.action.href}>{banner.action.label}</Link>
        </Button>
      ) : null}
      {onDismiss ? (
        <Button variant="ghost" size="sm" onClick={onDismiss}>
          {t('banners.dismiss')}
        </Button>
      ) : null}
    </div>
  );
}

/**
 * The unread notices (BR-14) in one box instead of one alert each: a single notice is shown in
 * full; several fold into a one-line summary that opens into a compact list.
 */
function NoticeBox({
  notices,
  onDismiss,
}: {
  notices: readonly Banner[];
  onDismiss: (ids: readonly string[]) => void;
}) {
  const [open, setOpen] = useState(false);
  const listId = useId();
  const pathname = usePathname();
  // A removed dish has to be reselected before the lock (BR-14), so the box says so.
  const variant = notices.some((n) => n.action) ? 'warning' : 'default';
  const [first] = notices;

  if (notices.length === 1 && first) {
    return (
      <Alert variant={variant} role="status">
        <Bell className="h-4 w-4" aria-hidden />
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between sm:gap-3">
          <span>{first.message}</span>
          <BannerActions banner={first} onDismiss={() => onDismiss([first.dismissId!])} />
        </div>
      </Alert>
    );
  }

  // Collapsed, the summary keeps the shortcut; open, each notice has its own.
  const action = open
    ? undefined
    : notices.find((n) => n.action && !pathname.startsWith(n.action.href))?.action;
  return (
    <Alert variant={variant} role="status">
      <Bell className="h-4 w-4" aria-hidden />
      <div className="flex flex-col gap-2">
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between sm:gap-3">
          <span className="font-medium">{t('banners.summary', { count: notices.length })}</span>
          <div className="flex shrink-0 flex-wrap gap-1">
            {action ? (
              <Button asChild variant="outline" size="sm">
                <Link href={action.href}>{action.label}</Link>
              </Button>
            ) : null}
            <Button
              variant="ghost"
              size="sm"
              aria-expanded={open}
              aria-controls={listId}
              onClick={() => setOpen((o) => !o)}
            >
              {open ? t('banners.collapse') : t('banners.expand')}
              {open ? (
                <ChevronUp className="h-4 w-4" aria-hidden />
              ) : (
                <ChevronDown className="h-4 w-4" aria-hidden />
              )}
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => onDismiss(notices.map((n) => n.dismissId!))}
            >
              {t('banners.dismissAll')}
            </Button>
          </div>
        </div>
        <ul
          id={listId}
          hidden={!open}
          // `flex` would override the hidden attribute's display: none.
          className={cn('flex-col divide-y divide-border/60', open ? 'flex' : 'hidden')}
        >
          {notices.map((n) => (
            <li
              key={n.id}
              className="flex flex-col gap-1 py-2 last:pb-0 sm:flex-row sm:items-center sm:justify-between sm:gap-3"
            >
              <span>{n.message}</span>
              <BannerActions banner={n} onDismiss={() => onDismiss([n.dismissId!])} />
            </li>
          ))}
        </ul>
      </div>
    </Alert>
  );
}
