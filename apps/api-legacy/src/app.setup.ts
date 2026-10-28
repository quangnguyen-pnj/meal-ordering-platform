import type { INestApplication } from '@nestjs/common';
import cookieParser from 'cookie-parser';
import type { NextFunction, Request, Response } from 'express';

/**
 * Every API answer is personal (and a PIN reset carries a temporary PIN), so nothing is kept in
 * the browser or a proxy cache; nosniff stops a download being run as a script.
 */
function securityHeaders(_req: Request, res: Response, next: NextFunction): void {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'same-origin');
  next();
}

/** Middleware and prefix shared by main.ts and the e2e tests, so tests run the real configuration. */
export function configureApp(app: INestApplication): void {
  app.setGlobalPrefix('api');
  app.getHttpAdapter().getInstance().disable('x-powered-by');
  app.use(securityHeaders);
  app.use(cookieParser());
}
