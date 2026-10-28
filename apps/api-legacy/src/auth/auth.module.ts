import { Module } from '@nestjs/common';
import { ENV, type AppEnv } from '../config/env';
import { AuthController } from './auth.controller';
import { EntraAuthService } from './entra-auth.service';
import { ENTRA_PROVIDER } from './entra/entra.provider';
import { MockEntraProvider } from './entra/mock-entra.provider';
import { OidcEntraProvider } from './entra/oidc-entra.provider';
import { PinAuthService } from './pin-auth.service';
import { SessionService } from './session.service';

@Module({
  controllers: [AuthController],
  providers: [
    SessionService,
    PinAuthService,
    EntraAuthService,
    {
      provide: ENTRA_PROVIDER,
      inject: [ENV],
      useFactory: (env: AppEnv) =>
        env.ENTRA_MODE === 'oidc' ? new OidcEntraProvider(env) : new MockEntraProvider(),
    },
  ],
  exports: [SessionService, PinAuthService],
})
export class AuthModule {}
