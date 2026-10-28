import { createHash, randomBytes } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';
import type { AuthMethod } from '@prisma/client';
import type { CookieOptions, Response } from 'express';
import { ClockService } from '../clock/clock.service';
import { ENV, type AppEnv } from '../config/env';
import { PrismaService } from '../prisma/prisma.service';

export const SESSION_COOKIE = 'meal_session';
const MS_PER_HOUR = 3_600_000;

export interface ActiveSession {
  id: string;
  employeeId: string;
  method: AuthMethod;
}

/** Postgres-backed sessions behind an httpOnly cookie. Only the SHA-256 of the token is stored. */
@Injectable()
export class SessionService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly clock: ClockService,
    @Inject(ENV) private readonly env: AppEnv,
  ) {}

  get secureCookies(): boolean {
    return this.env.COOKIE_SECURE;
  }

  hashToken(token: string): string {
    return createHash('sha256').update(token).digest('hex');
  }

  async create(
    employeeId: string,
    method: AuthMethod,
    userAgent?: string,
  ): Promise<{ id: string; token: string; expiresAt: Date }> {
    const now = this.clock.now();
    const token = randomBytes(32).toString('base64url');
    const expiresAt = new Date(now.getTime() + this.env.SESSION_TTL_HOURS * MS_PER_HOUR);
    // Housekeeping: expired sessions are not business data.
    await this.prisma.authSession.deleteMany({ where: { expiresAt: { lt: now } } });
    const row = await this.prisma.authSession.create({
      data: {
        employeeId,
        method,
        tokenHash: this.hashToken(token),
        expiresAt,
        userAgent: userAgent?.slice(0, 255),
      },
    });
    return { id: row.id, token, expiresAt };
  }

  async findActive(token: string): Promise<ActiveSession | null> {
    const row = await this.prisma.authSession.findUnique({
      where: { tokenHash: this.hashToken(token) },
    });
    if (!row || row.expiresAt.getTime() <= this.clock.now().getTime()) return null;
    return { id: row.id, employeeId: row.employeeId, method: row.method };
  }

  async revoke(sessionId: string): Promise<void> {
    await this.prisma.authSession.deleteMany({ where: { id: sessionId } });
  }

  async revokeAllForEmployee(employeeId: string, exceptSessionId?: string): Promise<void> {
    await this.prisma.authSession.deleteMany({
      where: { employeeId, ...(exceptSessionId ? { id: { not: exceptSessionId } } : {}) },
    });
  }

  private cookieOptions(): CookieOptions {
    return {
      httpOnly: true,
      sameSite: 'lax',
      secure: this.env.COOKIE_SECURE,
      path: '/',
    };
  }

  setCookie(res: Response, token: string, expiresAt: Date): void {
    res.cookie(SESSION_COOKIE, token, { ...this.cookieOptions(), expires: expiresAt });
  }

  clearCookie(res: Response): void {
    res.clearCookie(SESSION_COOKIE, this.cookieOptions());
  }
}
