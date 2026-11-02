import { PERMISSION_MATRIX, type Action, type Role } from '@meal/shared';
import type { MessageKey } from '@/messages';

export type NavGroup = 'mine' | 'admin' | 'masterdata';

export interface NavItem {
  href: string;
  labelKey: MessageKey;
  group: NavGroup;
  /** Matrix action that decides visibility (plan section 3). The API still enforces it. */
  action: Action;
  /** Phase that completes the screen; the catch-all placeholder shows it until a page exists. */
  phase: string;
  /** False while the screen is only a placeholder: the menu greys it out with "Sắp có". */
  available: boolean;
}

export const NAV_ITEMS: readonly NavItem[] = [
  // MINE
  {
    href: '/today',
    labelKey: 'nav.items.today',
    group: 'mine',
    action: 'menu.registerOwn',
    phase: 'P2',
    available: true,
  },
  {
    href: '/week',
    labelKey: 'nav.items.week',
    group: 'mine',
    action: 'menu.registerOwn',
    phase: 'P2',
    available: true,
  },
  {
    href: '/team',
    labelKey: 'nav.items.team',
    group: 'mine',
    action: 'team.view',
    phase: 'P2',
    available: true,
  },
  {
    href: '/history',
    labelKey: 'nav.items.history',
    group: 'mine',
    action: 'menu.registerOwn',
    phase: 'P2',
    available: true,
  },

  // ADMIN
  {
    href: '/admin/today',
    labelKey: 'nav.items.adminToday',
    group: 'admin',
    action: 'presence.confirm',
    phase: 'P3',
    available: false,
  },
  {
    href: '/admin/registrations',
    labelKey: 'nav.items.registrations',
    group: 'admin',
    action: 'registration.onBehalf',
    phase: 'P2',
    available: true,
  },
  {
    href: '/admin/print',
    labelKey: 'nav.items.print',
    group: 'admin',
    action: 'registration.onBehalf',
    phase: 'P2',
    available: true,
  },
  {
    href: '/admin/reconciliation',
    labelKey: 'nav.items.reconciliation',
    group: 'admin',
    action: 'reconciliation.view',
    phase: 'P4',
    available: false,
  },
  {
    href: '/admin/delivery',
    labelKey: 'nav.items.delivery',
    group: 'admin',
    action: 'delivery.confirmUnit',
    phase: 'P5',
    available: false,
  },
  {
    href: '/admin/reports',
    labelKey: 'nav.items.reports',
    group: 'admin',
    action: 'reports.view',
    phase: 'P6',
    available: false,
  },
  {
    href: '/admin/audit',
    labelKey: 'nav.items.audit',
    group: 'admin',
    action: 'audit.view',
    phase: 'P6',
    available: false,
  },
  {
    href: '/system',
    labelKey: 'nav.items.system',
    group: 'admin',
    action: 'integration.configure',
    phase: 'P1',
    available: true,
  },

  // MASTER DATA
  {
    href: '/admin/dishes',
    labelKey: 'nav.items.dishes',
    group: 'masterdata',
    action: 'dishes.manage',
    phase: 'P1',
    available: true,
  },
  {
    href: '/admin/menu',
    labelKey: 'nav.items.menu',
    group: 'masterdata',
    action: 'menu.manage',
    phase: 'P1',
    available: true,
  },
  {
    href: '/admin/master',
    labelKey: 'nav.items.master',
    group: 'masterdata',
    action: 'org.manage',
    phase: 'P1',
    available: true,
  },
  {
    href: '/admin/org',
    labelKey: 'nav.items.org',
    group: 'masterdata',
    action: 'org.manage',
    phase: 'P1',
    available: true,
  },
  {
    href: '/admin/employees',
    labelKey: 'nav.items.employees',
    group: 'masterdata',
    action: 'pin.reset',
    phase: 'P1',
    available: true,
  },
  {
    href: '/admin/settings',
    labelKey: 'nav.items.settings',
    group: 'masterdata',
    action: 'settings.manage',
    phase: 'P1',
    available: true,
  },
];

/** True when any of the roles holds some grant for the action (scope is checked by the API). */
export function rolesCanSee(roles: readonly Role[], action: Action): boolean {
  const grants = PERMISSION_MATRIX[action];
  return roles.some((role) => grants[role] !== undefined);
}

export function isNavItemVisible(item: NavItem, roles: readonly Role[]): boolean {
  return rolesCanSee(roles, item.action);
}

export function visibleNav(roles: readonly Role[]): Record<NavGroup, NavItem[]> {
  const visible = NAV_ITEMS.filter((item) => isNavItemVisible(item, roles));
  return {
    mine: visible.filter((i) => i.group === 'mine'),
    admin: visible.filter((i) => i.group === 'admin'),
    masterdata: visible.filter((i) => i.group === 'masterdata'),
  };
}

export function findNavItem(pathname: string): NavItem | undefined {
  return NAV_ITEMS.find((i) => pathname === i.href || pathname.startsWith(`${i.href}/`));
}
