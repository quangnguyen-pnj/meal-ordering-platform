import type { Action, ListScope, Principal, Role } from '@meal/shared';
import type { Request } from 'express';

/** Principal plus the profile data the API and /auth/me need. */
export interface RequestPrincipal extends Principal {
  sessionId: string;
  employeeCode: string;
  name: string;
  email: string | null;
  factoryId: string;
  mealUnitId: string | null;
  deptAdminOrgUnitIds: string[];
  /** True only for PIN sessions of an employee whose PIN is temporary (first sign-in or reset). */
  mustChangePin: boolean;
  roles: Role[];
}

export interface AuthedRequest extends Request {
  principal?: RequestPrincipal;
  listScope?: ListScope;
}

/**
 * How a route's scope is derived. The guard resolves it and checks the
 * (role, action, scope) triple; handlers never re-implement the check.
 */
export type ScopeSpec =
  | { type: 'system' }
  /** The caller's own factory (single factory in the MVP). */
  | { type: 'factory' }
  | { type: 'self' }
  /** Route param holds an employee code; the scope is that employee's org unit. */
  | { type: 'employeeParam'; param: string }
  | { type: 'orgUnitParam'; param: string }
  /** A list endpoint: the handler filters by `req.listScope` (see resolveListScope). */
  | { type: 'list' };

export interface PermissionMeta {
  action: Action;
  scope: ScopeSpec;
}
