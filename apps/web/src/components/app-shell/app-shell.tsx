'use client';

import type { BannerView, SessionUser } from '@/lib/api-types';
import { ChevronDown, LogOut, Menu, PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import Link, { useLinkStatus } from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Sheet, SheetContent, SheetDescription, SheetTitle } from '@/components/ui/sheet';
import { Spinner } from '@/components/ui/skeleton';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { api } from '@/lib/api';
import { visibleNav, type NavItem } from '@/lib/nav';
import { cn } from '@/lib/utils';
import { t, vi } from '@/messages';
import { BannerArea } from './banner-area';

/** Below this width (tablet and phone) the menu is a drawer; from here up it is a sidebar. */
const DESKTOP_QUERY = '(min-width: 1024px)';
const COLLAPSED_KEY = 'meal.sidebar.collapsed';

// The icon keeps the same x position collapsed or not, so it never jumps while the width animates.
const itemClass =
  'flex min-h-11 items-center gap-2 overflow-hidden whitespace-nowrap rounded-md px-3 text-sm';

/** Label that fades out as the sidebar narrows; the sidebar's own overflow clips what is left. */
function NavLabel({ collapsed, children }: { collapsed: boolean; children: React.ReactNode }) {
  return (
    <span
      className={cn(
        'flex flex-1 items-center gap-2 transition-opacity duration-200',
        collapsed && 'opacity-0',
      )}
      aria-hidden={collapsed || undefined}
    >
      {children}
    </span>
  );
}

/** Icon, or a spinner in its place once the user has clicked and the new page is still loading. */
function LinkIcon({ icon: Icon }: { icon: NavItem['icon'] }) {
  const { pending } = useLinkStatus();
  return pending ? (
    <Spinner className="h-4 w-4 shrink-0 text-muted-foreground" />
  ) : (
    <Icon className="h-4 w-4 shrink-0" aria-hidden />
  );
}

function NavLink({
  item,
  active,
  collapsed,
  onNavigate,
}: {
  item: NavItem;
  active: boolean;
  collapsed: boolean;
  onNavigate: () => void;
}) {
  const label = t(item.labelKey);
  if (!item.available) {
    // Placeholder screens stay listed so people know they are coming, but are not links.
    return (
      <Tooltip>
        <TooltipTrigger asChild>
          <span
            aria-disabled="true"
            aria-label={collapsed ? label : undefined}
            className={cn(itemClass, 'cursor-default text-muted-foreground/70')}
          >
            <item.icon className="h-4 w-4 shrink-0" aria-hidden />
            <NavLabel collapsed={collapsed}>
              {label}
              <Badge variant="outline" className="ml-auto shrink-0 text-[0.6875rem] font-normal">
                {t('nav.comingSoon')}
              </Badge>
            </NavLabel>
          </span>
        </TooltipTrigger>
        <TooltipContent side="right">
          {collapsed ? `${label} · ` : ''}
          {t('nav.comingSoonHint', { phase: item.phase })}
        </TooltipContent>
      </Tooltip>
    );
  }
  const link = (
    <Link
      href={item.href}
      onClick={onNavigate}
      aria-current={active ? 'page' : undefined}
      aria-label={collapsed ? label : undefined}
      className={cn(
        itemClass,
        'transition-colors hover:bg-accent',
        active && 'bg-accent font-medium text-accent-foreground',
      )}
    >
      <LinkIcon icon={item.icon} />
      <NavLabel collapsed={collapsed}>{label}</NavLabel>
    </Link>
  );
  if (!collapsed) return link;
  // Icons alone are ambiguous, so the collapsed sidebar names each one on hover and focus.
  return (
    <Tooltip>
      <TooltipTrigger asChild>{link}</TooltipTrigger>
      <TooltipContent side="right">{label}</TooltipContent>
    </Tooltip>
  );
}

function roleLabels(user: SessionUser): string {
  return user.roles
    .filter((r) => r !== 'EMPLOYEE')
    .map((r) => vi.roles[r])
    .join(', ');
}

/** Vietnamese names put the given name last, so the avatar shows its first letter. */
function initial(name: string): string {
  const given = name.trim().split(/\s+/).at(-1) ?? '';
  return given.charAt(0).toLocaleUpperCase('vi-VN') || '?';
}

/** True once the window has scrolled away from the top. */
function useScrolled(): boolean {
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const update = () => setScrolled(window.scrollY > 0);
    update();
    window.addEventListener('scroll', update, { passive: true });
    return () => window.removeEventListener('scroll', update);
  }, []);
  return scrolled;
}

/** The sidebar's collapsed choice, kept per browser. Read after mount so server and client agree. */
function useCollapsed(): { collapsed: boolean; toggle: () => void; ready: boolean } {
  const [collapsed, setCollapsed] = useState(false);
  const [ready, setReady] = useState(false);
  useEffect(() => {
    try {
      setCollapsed(window.localStorage.getItem(COLLAPSED_KEY) === '1');
    } catch {
      // Storage can be blocked (private window); the sidebar then just starts expanded.
    }
    // Animate only changes the user makes, not the first paint.
    const frame = requestAnimationFrame(() => setReady(true));
    return () => cancelAnimationFrame(frame);
  }, []);
  const toggle = () =>
    setCollapsed((prev) => {
      try {
        window.localStorage.setItem(COLLAPSED_KEY, prev ? '0' : '1');
      } catch {
        // Not persisted; still works for this visit.
      }
      return !prev;
    });
  return { collapsed, toggle, ready };
}

/** Desktop: the account sits behind one button so sign-out is never a stray click. */
function AccountMenu({ user, onSignOut }: { user: SessionUser; onSignOut: () => void }) {
  const roles = roleLabels(user);
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          className="h-auto gap-2 py-1.5 pl-1.5 pr-2"
          aria-label={`${t('common.account')}: ${user.name}`}
        >
          <span
            aria-hidden
            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary text-sm font-semibold text-primary-foreground"
          >
            {initial(user.name)}
          </span>
          <span className="min-w-0 max-w-48 truncate text-sm font-medium">{user.name}</span>
          <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-64">
        <DropdownMenuLabel className="font-normal">
          <p className="truncate font-medium">{user.name}</p>
          <p className="truncate text-xs text-muted-foreground">
            {user.employeeCode}
            {roles ? ` · ${roles}` : ''}
          </p>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={onSignOut}>
          <LogOut className="h-4 w-4" aria-hidden />
          {t('common.signOut')}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** Phones: the account block at the foot of the menu sheet. */
function UserBlock({ user, onSignOut }: { user: SessionUser; onSignOut: () => void }) {
  const roles = roleLabels(user);
  return (
    <>
      <div className="min-w-0 text-sm leading-tight md:text-right">
        <p className="truncate font-medium">{user.name}</p>
        <p className="truncate text-xs text-muted-foreground">
          {user.employeeCode}
          {roles ? ` · ${roles}` : ''}
        </p>
      </div>
      <Button variant="outline" size="sm" onClick={onSignOut} className="shrink-0">
        <LogOut className="h-4 w-4" aria-hidden />
        {t('common.signOut')}
      </Button>
    </>
  );
}

export function AppShell({
  user,
  banners,
  children,
}: {
  user: SessionUser;
  banners: readonly BannerView[];
  children: React.ReactNode;
}) {
  const pathname = usePathname();
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const scrolled = useScrolled();
  const { collapsed, toggle, ready } = useCollapsed();

  // Rotating a tablet or resizing past the breakpoint swaps the drawer for the sidebar; close the
  // drawer so its overlay and scroll lock do not linger behind it.
  useEffect(() => {
    const query = window.matchMedia(DESKTOP_QUERY);
    const onChange = (e: MediaQueryListEvent) => e.matches && setOpen(false);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);
  const nav = visibleNav(user.roles);
  const groups = [
    { key: 'mine', title: t('nav.groups.mine'), items: nav.mine },
    { key: 'admin', title: t('nav.groups.admin'), items: nav.admin },
    { key: 'masterdata', title: 'Master Data', items: nav.masterdata },
  ].filter((g) => g.items.length > 0);

  async function signOut() {
    await api.post('/auth/logout').catch(() => undefined);
    router.replace('/login');
    router.refresh();
  }

  /** The drawer is always full width; only the desktop sidebar collapses. */
  const renderNav = (isCollapsed: boolean) => (
    <nav aria-label={t('nav.label')} className="flex flex-col gap-6 px-2 py-4 lg:pt-0">
      {groups.map((group) => (
        <div key={group.key}>
          {/* Group title fades into a thin rule when collapsed, so the height never changes. */}
          <div className="relative h-6 px-3">
            <p
              className={cn(
                'whitespace-nowrap pb-2 text-[11px] font-bold uppercase tracking-wide text-neutral-400 transition-opacity duration-200',
                isCollapsed && 'opacity-0',
              )}
              aria-hidden={isCollapsed || undefined}
            >
              {group.title}
            </p>
            <div
              aria-hidden
              className={cn(
                'absolute inset-x-3 top-2 h-px bg-border opacity-0 transition-opacity duration-200',
                isCollapsed && 'opacity-100',
              )}
            />
          </div>
          <ul className="flex flex-col gap-1 font-medium">
            {group.items.map((item) => (
              <li key={item.href}>
                <NavLink
                  item={item}
                  active={pathname === item.href || pathname.startsWith(`${item.href}/`)}
                  collapsed={isCollapsed}
                  onNavigate={() => setOpen(false)}
                />
              </li>
            ))}
          </ul>
        </div>
      ))}
    </nav>
  );

  return (
    <div className="min-h-screen lg:flex">
      <header className="sticky top-0 z-30 flex h-14 items-center justify-between bg-card px-4 shadow-block lg:hidden print:hidden">
        <span className="font-semibold">{t('app.name')}</span>
        <Button
          variant="ghost"
          size="icon"
          aria-expanded={open}
          aria-controls="mobile-nav"
          aria-label={t('common.menu')}
          onClick={() => setOpen(true)}
        >
          <Menu className="h-5 w-5" />
        </Button>
      </header>

      {/* Phones and tablets: the menu slides in from the left and also holds the account block. */}
      <Sheet open={open} onOpenChange={setOpen}>
        <SheetContent id="mobile-nav" side="left" className="gap-0 p-0 lg:hidden">
          <div className="px-4 py-4">
            <SheetTitle>{t('app.name')}</SheetTitle>
            <SheetDescription>{t('app.factory')}</SheetDescription>
          </div>
          <div className="flex-1 overflow-y-auto">{renderNav(false)}</div>
          <div className="flex items-center justify-between gap-3 bg-muted/40 p-4">
            <UserBlock user={user} onSignOut={signOut} />
          </div>
        </SheetContent>
      </Sheet>

      {/* Desktop: the sidebar narrows to a column of icons; the width animates, labels fade. */}
      <aside
        data-collapsed={collapsed}
        className={cn(
          'hidden overflow-y-auto overflow-x-hidden bg-card shadow-block lg:sticky lg:top-0 lg:z-30 lg:flex lg:h-screen lg:shrink-0 lg:flex-col print:hidden',
          collapsed ? 'lg:w-16' : 'lg:w-64',
          ready && 'transition-[width] duration-300 ease-in-out motion-reduce:transition-none',
        )}
      >
        {/* The app name stays put while a long menu scrolls under it (no shadow, by choice). */}
        <div className="sticky top-0 z-10 flex items-center gap-2 bg-card px-3 py-4">
          <Button
            variant="ghost"
            size="icon"
            className="shrink-0"
            aria-expanded={!collapsed}
            aria-label={collapsed ? t('nav.expand') : t('nav.collapse')}
            onClick={toggle}
          >
            {collapsed ? (
              <PanelLeftOpen className="h-5 w-5" aria-hidden />
            ) : (
              <PanelLeftClose className="h-5 w-5" aria-hidden />
            )}
          </Button>
          <div
            className={cn(
              'min-w-0 whitespace-nowrap transition-opacity duration-200',
              collapsed && 'opacity-0',
            )}
            aria-hidden={collapsed || undefined}
          >
            <p className="text-lg font-semibold leading-tight">{t('app.name')}</p>
            <p className="text-sm text-muted-foreground">{t('app.factory')}</p>
          </div>
        </div>
        {renderNav(collapsed)}
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        {/* Same canvas as the page so it reads as part of it; a soft shadow appears once content scrolls under. */}
        <div
          className={cn(
            'sticky top-0 z-20 hidden items-center justify-end gap-3 bg-canvas/85 px-4 py-2 backdrop-blur-md transition-shadow lg:flex lg:px-6 print:hidden',
            scrolled && 'shadow-[0_8px_16px_-12px_rgb(41_37_36/0.3)]',
          )}
        >
          <AccountMenu user={user} onSignOut={signOut} />
        </div>
        {/* The sticky account bar above already gives the page its top space on desktop. */}
        <main className="flex flex-1 flex-col gap-5 p-4 md:gap-6 md:px-8 md:pb-8 lg:pt-3 print:p-0">
          {/* Paper forms (/admin/print) print the page content only. Takes no gap when there is no banner. */}
          <div className="empty:hidden print:hidden">
            <BannerArea userId={user.id} initial={banners} />
          </div>
          {children}
        </main>
      </div>
    </div>
  );
}
