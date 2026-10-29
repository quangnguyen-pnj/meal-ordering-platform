'use client';

import type { BannerView, SessionUser } from '@/lib/api-types';
import { LogOut, Menu } from 'lucide-react';
import Link, { useLinkStatus } from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Sheet, SheetContent, SheetDescription, SheetTitle } from '@/components/ui/sheet';
import { Spinner } from '@/components/ui/skeleton';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { api } from '@/lib/api';
import { visibleNav, type NavItem } from '@/lib/nav';
import { cn } from '@/lib/utils';
import { t, vi } from '@/messages';
import { BannerArea } from './banner-area';

const itemClass = 'flex min-h-11 items-center gap-2 rounded-md px-3 text-sm';

/** Spinner next to the link the user just clicked, until the new page has rendered. */
function PendingMark() {
  const { pending } = useLinkStatus();
  return pending ? <Spinner className="ml-auto text-muted-foreground" /> : null;
}

function NavLink({
  item,
  active,
  onNavigate,
}: {
  item: NavItem;
  active: boolean;
  onNavigate: () => void;
}) {
  if (!item.available) {
    // Placeholder screens stay listed so people know they are coming, but are not links.
    return (
      <Tooltip>
        <TooltipTrigger asChild>
          <span
            aria-disabled="true"
            className={cn(itemClass, 'cursor-default text-muted-foreground/70')}
          >
            {t(item.labelKey)}
            <Badge variant="outline" className="ml-auto text-[0.6875rem] font-normal">
              {t('nav.comingSoon')}
            </Badge>
          </span>
        </TooltipTrigger>
        <TooltipContent side="right">
          {t('nav.comingSoonHint', { phase: item.phase })}
        </TooltipContent>
      </Tooltip>
    );
  }
  return (
    <Link
      href={item.href}
      onClick={onNavigate}
      aria-current={active ? 'page' : undefined}
      className={cn(
        itemClass,
        'transition-colors hover:bg-accent',
        active && 'bg-accent font-medium text-accent-foreground',
      )}
    >
      {t(item.labelKey)}
      <PendingMark />
    </Link>
  );
}

function UserBlock({ user, onSignOut }: { user: SessionUser; onSignOut: () => void }) {
  const roleLabels = user.roles
    .filter((r) => r !== 'EMPLOYEE')
    .map((r) => vi.roles[r])
    .join(', ');
  return (
    <>
      <div className="min-w-0 text-sm leading-tight md:text-right">
        <p className="truncate font-medium">{user.name}</p>
        <p className="truncate text-xs text-muted-foreground">
          {user.employeeCode}
          {roleLabels ? ` · ${roleLabels}` : ''}
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
  const nav = visibleNav(user.roles);
  const groups = [
    { key: 'mine', title: t('nav.groups.mine'), items: nav.mine },
    { key: 'admin', title: t('nav.groups.admin'), items: nav.admin },
  ].filter((g) => g.items.length > 0);

  async function signOut() {
    await api.post('/auth/logout').catch(() => undefined);
    router.replace('/login');
    router.refresh();
  }

  const navigation = (
    <nav aria-label={t('nav.label')} className="flex flex-col gap-6 px-2 py-4 md:pt-0">
      {groups.map((group) => (
        <div key={group.key}>
          <p className="px-3 pb-2 text-xs font-bold uppercase tracking-wide text-muted-foreground">
            {group.title}
          </p>
          <ul className="flex flex-col gap-1 font-medium">
            {group.items.map((item) => (
              <li key={item.href}>
                <NavLink
                  item={item}
                  active={pathname === item.href || pathname.startsWith(`${item.href}/`)}
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
    <div className="min-h-screen md:flex">
      <header className="sticky top-0 z-30 flex h-14 items-center justify-between bg-card px-4 shadow-block md:hidden print:hidden">
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

      {/* Phones: the menu slides in from the left and also holds the account block. */}
      <Sheet open={open} onOpenChange={setOpen}>
        <SheetContent id="mobile-nav" side="left" className="gap-0 p-0 md:hidden">
          <div className="px-4 py-4">
            <SheetTitle>{t('app.name')}</SheetTitle>
            <SheetDescription>{t('app.factory')}</SheetDescription>
          </div>
          <div className="flex-1 overflow-y-auto">{navigation}</div>
          <div className="flex items-center justify-between gap-3 bg-muted/40 p-4">
            <UserBlock user={user} onSignOut={signOut} />
          </div>
        </SheetContent>
      </Sheet>

      <aside className="hidden bg-card shadow-block md:sticky md:top-0 md:flex md:h-screen md:w-64 md:shrink-0 md:flex-col md:overflow-y-auto print:hidden">
        <div className="px-4 py-5">
          <p className="text-lg font-semibold">{t('app.name')}</p>
          <p className="text-sm text-muted-foreground">{t('app.factory')}</p>
        </div>
        {navigation}
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <div className="hidden items-center justify-end gap-3 px-4 py-2 md:flex md:px-6 print:hidden">
          <UserBlock user={user} onSignOut={signOut} />
        </div>
        <main className="flex flex-1 flex-col gap-5 p-4 md:gap-6 md:p-8 print:p-0">
          {/* Paper forms (/admin/print) print the page content only. */}
          <div className="print:hidden">
            <BannerArea userId={user.id} initial={banners} />
          </div>
          {children}
        </main>
      </div>
    </div>
  );
}
