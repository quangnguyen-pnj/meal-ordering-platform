import 'reflect-metadata';
import { Logger } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';
import { configureApp } from './app.setup';
import { loadEnv } from './config/env';

async function bootstrap(): Promise<void> {
  const env = loadEnv();
  const app = await NestFactory.create(AppModule.forRoot(env));
  configureApp(app);
  // Behind nginx: trust X-Forwarded-* so secure cookies and client IPs work.
  app.getHttpAdapter().getInstance().set('trust proxy', 1);
  app.enableShutdownHooks();
  await app.listen(env.API_PORT, '0.0.0.0');
  new Logger('Bootstrap').log(`API listening on :${env.API_PORT} (NODE_ENV=${env.NODE_ENV})`);
}

void bootstrap();
