import {
  HttpStatus,
  Injectable,
  Logger,
  type CanActivate,
  type ExecutionContext,
} from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { authorize, hasAnyGrant, resolveListScope } from '@meal/shared';
import { SESSION_COOKIE, SessionService } from '../auth/session.service';
import { ApiException } from '../common/api-error';
import {
  ALLOW_PIN_CHANGE_KEY,
  AUTHENTICATED_KEY,
  IS_PUBLIC_KEY,
  PERMISSION_KEY,
} from './decorators';
import { PrincipalService } from './principal.service';
import type { AuthedRequest, PermissionMeta } from './rbac.types';
import { ScopeResolver } from './scope-resolver.service';

/**
 * Global guard: authentication, the forced PIN change, and the (role, action, scope) check from
 * plan section 3. Permissions are NOT checked inside handlers (plan section 3, implementation rules).
 * A route that declares none of @Public / @Authenticated / @RequirePermission is denied.
 */
@Injectable()
export class AccessGuard implements CanActivate {
  private readonly logger = new Logger(AccessGuard.name);

  constructor(
    private readonly reflector: Reflector,
    private readonly sessions: SessionService,
    private readonly principals: PrincipalService,
    private readonly scopes: ScopeResolver,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const targets = [context.getHandler(), context.getClass()];
    if (this.reflector.getAllAndOverride<boolean>(IS_PUBLIC_KEY, targets)) return true;

    const req = context.switchToHttp().getRequest<AuthedRequest>();
    const token: unknown = req.cookies?.[SESSION_COOKIE];
    const session =
      typeof token === 'string' && token ? await this.sessions.findActive(token) : null;
    const principal = session ? await this.principals.load(session.employeeId, session) : null;
    if (!principal) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, 'UNAUTHENTICATED', 'Sign in required');
    }
    req.principal = principal;

    if (
      principal.mustChangePin &&
      !this.reflector.getAllAndOverride<boolean>(ALLOW_PIN_CHANGE_KEY, targets)
    ) {
      throw new ApiException(HttpStatus.FORBIDDEN, 'PIN_CHANGE_REQUIRED', 'PIN change required');
    }

    const permission = this.reflector.getAllAndOverride<PermissionMeta | undefined>(
      PERMISSION_KEY,
      targets,
    );
    if (!permission) {
      if (this.reflector.getAllAndOverride<boolean>(AUTHENTICATED_KEY, targets)) return true;
      this.logger.error(
        `Route ${req.method} ${req.originalUrl} has no access metadata; denying (fail closed)`,
      );
      throw new ApiException(HttpStatus.FORBIDDEN, 'FORBIDDEN', 'Access denied');
    }

    const { action, scope: spec } = permission;
    if (spec.type === 'list') {
      if (!hasAnyGrant(principal, action)) this.deny();
      const listScope = resolveListScope(principal, action);
      if (listScope.kind === 'none') this.deny();
      req.listScope = listScope;
      return true;
    }

    const scope = await this.scopes.resolve(spec, principal, req);
    if (scope === null) {
      // Only callers who could see the whole population learn that a resource does not exist;
      // everyone else gets 403 so existence is not leaked outside their scope.
      if (principal.hcFactoryIds.length > 0 || principal.roles.includes('SYS_ADMIN')) {
        throw new ApiException(HttpStatus.NOT_FOUND, 'NOT_FOUND', 'Not found');
      }
      this.deny();
    }
    if (!authorize(principal, action, scope)) this.deny();
    return true;
  }

  private deny(): never {
    throw new ApiException(HttpStatus.FORBIDDEN, 'FORBIDDEN', 'Access denied');
  }
}
