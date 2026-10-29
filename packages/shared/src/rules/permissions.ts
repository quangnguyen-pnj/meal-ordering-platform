import type { Role } from '../enums';

/**
 * Permission matrix from plan section 3, checked as a (role, action, scope) triple by the API
 * (vn.pnj.meal.rules.access.Action). The UI only uses it to hide what a role cannot do.
 */

export const ACTIONS = [
  'menu.registerOwn',
  'team.view',
  'registration.onBehalf',
  'registration.addExtra',
  'presence.confirm',
  'registration.notRegisteredList',
  'dishes.manage',
  'menu.manage',
  'reconciliation.view',
  'day.finalize',
  'export.finalAndForecast',
  'adjustment.manage',
  'delivery.confirmUnit',
  'delivery.confirmFinal',
  'reports.view',
  'pin.reset',
  'org.manage',
  'settings.manage',
  'sync.manage',
  'audit.view',
  'roles.assignPrivileged',
  'integration.configure',
] as const;
export type Action = (typeof ACTIONS)[number];

/**
 * How far a role's grant reaches.
 * SELF: the caller's own data.
 * OWN_UNIT: the caller's meal unit.
 * UNITS: assigned org nodes and their children.
 * FACTORY: the whole factory.
 * SYSTEM: everything.
 */
export type ScopeLevel = 'SELF' | 'OWN_UNIT' | 'UNITS' | 'FACTORY' | 'SYSTEM';

type Grants = Partial<Record<Role, ScopeLevel>>;

const ALL_SELF: Grants = {
  EMPLOYEE: 'SELF',
  DEPT_ADMIN: 'SELF',
  HC_ADMIN: 'SELF',
  SYS_ADMIN: 'SELF',
};
const UNITS_OR_FACTORY: Grants = { DEPT_ADMIN: 'UNITS', HC_ADMIN: 'FACTORY' };
const HC_ONLY: Grants = { HC_ADMIN: 'FACTORY' };
const HC_OR_SYSTEM: Grants = { HC_ADMIN: 'FACTORY', SYS_ADMIN: 'SYSTEM' };
const SYSTEM_ONLY: Grants = { SYS_ADMIN: 'SYSTEM' };

export const PERMISSION_MATRIX: Readonly<Record<Action, Grants>> = {
  'menu.registerOwn': ALL_SELF,
  'team.view': { EMPLOYEE: 'OWN_UNIT', DEPT_ADMIN: 'UNITS', HC_ADMIN: 'FACTORY' },
  'registration.onBehalf': UNITS_OR_FACTORY,
  'registration.addExtra': UNITS_OR_FACTORY,
  'presence.confirm': UNITS_OR_FACTORY,
  'registration.notRegisteredList': UNITS_OR_FACTORY,
  'dishes.manage': HC_ONLY,
  'menu.manage': HC_ONLY,
  'reconciliation.view': UNITS_OR_FACTORY,
  'day.finalize': HC_ONLY,
  'export.finalAndForecast': HC_ONLY,
  'adjustment.manage': HC_ONLY,
  'delivery.confirmUnit': UNITS_OR_FACTORY,
  'delivery.confirmFinal': HC_ONLY,
  'reports.view': UNITS_OR_FACTORY,
  'pin.reset': { DEPT_ADMIN: 'UNITS', HC_ADMIN: 'FACTORY', SYS_ADMIN: 'SYSTEM' },
  'org.manage': HC_OR_SYSTEM,
  'settings.manage': HC_OR_SYSTEM,
  'sync.manage': HC_OR_SYSTEM,
  'audit.view': HC_OR_SYSTEM,
  'roles.assignPrivileged': SYSTEM_ONLY,
  'integration.configure': SYSTEM_ONLY,
};

/**
 * Org-tree paths are materialized as `/<id>/<id>/.../` with a trailing slash, so
 * `child.startsWith(ancestor)` is a safe "is within" test (no `/1/2/` vs `/1/22/` confusion).
 */
