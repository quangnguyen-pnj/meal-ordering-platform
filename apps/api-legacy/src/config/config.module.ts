import { Global, Module, type DynamicModule } from '@nestjs/common';
import { ENV, type AppEnv } from './env';

@Global()
@Module({})
export class ConfigModule {
  static forRoot(env: AppEnv): DynamicModule {
    return {
      module: ConfigModule,
      providers: [{ provide: ENV, useValue: env }],
      exports: [ENV],
    };
  }
}
