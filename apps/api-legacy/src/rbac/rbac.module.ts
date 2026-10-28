import { Global, Module } from '@nestjs/common';
import { APP_GUARD } from '@nestjs/core';
import { AuthModule } from '../auth/auth.module';
import { AccessGuard } from './access.guard';
import { PrincipalService } from './principal.service';
import { ScopeResolver } from './scope-resolver.service';
import { SystemAdminBootstrap } from './system-admin-bootstrap.service';

@Global()
@Module({
  imports: [AuthModule],
  providers: [
    PrincipalService,
    ScopeResolver,
    SystemAdminBootstrap,
    AccessGuard,
    { provide: APP_GUARD, useExisting: AccessGuard },
  ],
  exports: [PrincipalService, ScopeResolver, SystemAdminBootstrap],
})
export class RbacModule {}
