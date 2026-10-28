import { Global, Module } from '@nestjs/common';
import { ENV, type AppEnv } from '../config/env';
import { DMP_CLIENT } from './dmp.client';
import { HttpDmpClient } from './http-dmp.client';
import { MockDmpClient } from './mock/mock-dmp.client';

@Global()
@Module({
  providers: [
    {
      provide: DMP_CLIENT,
      inject: [ENV],
      useFactory: (env: AppEnv) =>
        env.DMP_MODE === 'http' ? new HttpDmpClient(env) : new MockDmpClient(env.DMP_MOCK_SCENARIO),
    },
  ],
  exports: [DMP_CLIENT],
})
export class DmpModule {}
