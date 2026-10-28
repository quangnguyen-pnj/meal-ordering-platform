import { Module, type DynamicModule } from '@nestjs/common';
import { APP_FILTER } from '@nestjs/core';
import { AuditModule } from './audit/audit.module';
import { AuthModule } from './auth/auth.module';
import { ApiExceptionFilter } from './common/api-error';
import { ConfigModule } from './config/config.module';
import type { AppEnv } from './config/env';
import { ClockModule } from './clock/clock.module';
import { DevClockModule } from './clock/dev-clock.module';
import { DishesModule } from './dishes/dishes.module';
import { DmpModule } from './dmp/dmp.module';
import { EmployeesModule } from './employees/employees.module';
import { HealthModule } from './health/health.module';
import { JobsModule } from './jobs/jobs.module';
import { MasterModule } from './master/master.module';
import { MeModule } from './me/me.module';
import { MenuModule } from './menu/menu.module';
import { OrgModule } from './org/org.module';
import { PrismaModule } from './prisma/prisma.service';
import { RbacModule } from './rbac/rbac.module';
import { RegistrationsModule } from './registrations/registrations.module';
import { SettingsModule } from './settings/settings.module';
import { SystemModule } from './system/system.module';

@Module({})
export class AppModule {
  static forRoot(env: AppEnv): DynamicModule {
    // Fake-time controls exist only where fake time is allowed (never in production).
    const devModules =
      env.NODE_ENV !== 'production' && env.CLOCK_FAKE_ENABLED ? [DevClockModule] : [];
    return {
      module: AppModule,
      imports: [
        ConfigModule.forRoot(env),
        PrismaModule,
        ClockModule,
        SettingsModule,
        AuditModule,
        DmpModule,
        AuthModule,
        RbacModule,
        EmployeesModule,
        MasterModule,
        OrgModule,
        DishesModule,
        MenuModule,
        RegistrationsModule,
        MeModule,
        JobsModule,
        SystemModule,
        HealthModule,
        ...devModules,
      ],
      providers: [{ provide: APP_FILTER, useClass: ApiExceptionFilter }],
    };
  }
}
