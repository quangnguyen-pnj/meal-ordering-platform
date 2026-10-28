import { SetMetadata, createParamDecorator, type ExecutionContext } from '@nestjs/common';
import type { Action } from '@meal/shared';
import type { AuthedRequest, PermissionMeta, RequestPrincipal, ScopeSpec } from './rbac.types';

export const IS_PUBLIC_KEY = 'access:public';
export const AUTHENTICATED_KEY = 'access:authenticated';
export const ALLOW_PIN_CHANGE_KEY = 'access:allowPinChange';
export const PERMISSION_KEY = 'access:permission';

/** No sign-in needed (login, health). */
export const Public = () => SetMetadata(IS_PUBLIC_KEY, true);

/** Any signed-in user; no role check (own profile, change PIN). */
export const Authenticated = () => SetMetadata(AUTHENTICATED_KEY, true);

/** Let a session with a temporary PIN through (change PIN, sign out, whoami). */
export const AllowPinChange = () => SetMetadata(ALLOW_PIN_CHANGE_KEY, true);

/**
 * Plan section 3: a route is guarded by a (role, action, scope) triple checked in AccessGuard.
 * Routes with none of Public / Authenticated / RequirePermission are denied (fail closed).
 */
export const RequirePermission = (action: Action, scope: ScopeSpec) =>
  SetMetadata(PERMISSION_KEY, { action, scope } satisfies PermissionMeta);

export const CurrentPrincipal = createParamDecorator(
  (_data: unknown, ctx: ExecutionContext): RequestPrincipal => {
    const req = ctx.switchToHttp().getRequest<AuthedRequest>();
    if (!req.principal) throw new Error('CurrentPrincipal used on a route without authentication');
    return req.principal;
  },
);
