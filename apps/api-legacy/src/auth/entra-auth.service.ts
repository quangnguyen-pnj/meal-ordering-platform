import { createHmac, timingSafeEqual } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';
import { extractEntraEmail } from '@meal/shared';
import { ClockService } from '../clock/clock.service';
import { ENV, type AppEnv } from '../config/env';
import { PrismaService } from '../prisma/prisma.service';
import { ENTRA_PROVIDER, type EntraProvider, type EntraTransaction } from './entra/entra.provider';
import { SessionService } from './session.service';

export const OIDC_COOKIE = 'meal_oidc';
const TX_TTL_MS = 10 * 60_000;

export type EntraSignInResult =
  | { ok: true; sessionId: string; token: string; expiresAt: Date }
  | { ok: false; reason: 'not-linked' | 'failed' };

@Injectable()
export class EntraAuthService {
  constructor(
    @Inject(ENTRA_PROVIDER) readonly provider: EntraProvider,
    @Inject(ENV) private readonly env: AppEnv,
    private readonly prisma: PrismaService,
    private readonly clock: ClockService,
    private readonly sessions: SessionService,
  ) {}

  async start(): Promise<{ redirectUrl: string; cookieValue: string }> {
    const { redirectUrl, transaction } = await this.provider.start();
    return { redirectUrl, cookieValue: this.sign(transaction) };
  }

  async complete(
    query: Record<string, string | undefined>,
    cookieValue: string | undefined,
    userAgent?: string,
  ): Promise<EntraSignInResult> {
    const tx = cookieValue ? this.verify(cookieValue) : null;
    if (!tx) return { ok: false, reason: 'failed' };

    let email: string | null;
    try {
      email = extractEntraEmail(await this.provider.complete(query, tx));
    } catch {
      return { ok: false, reason: 'failed' };
    }
    if (!email) return { ok: false, reason: 'not-linked' };

    // Case-insensitive match on the DMP email (plan section 5). Zero or ambiguous matches are
    // both "not linked": the HC admin or a system admin resolves them.
    const matches = await this.prisma.employee.findMany({
      where: { email: { equals: email, mode: 'insensitive' }, status: 'ACTIVE' },
      select: { id: true },
      take: 2,
    });
    const match = matches.length === 1 ? matches[0] : undefined;
    if (!match) return { ok: false, reason: 'not-linked' };

    const session = await this.sessions.create(match.id, 'ENTRA', userAgent);
    return { ok: true, sessionId: session.id, token: session.token, expiresAt: session.expiresAt };
  }

  /** `<base64url(json)>.<hmac>`: the PKCE verifier must not be forgeable or readable by scripts. */
  private sign(tx: EntraTransaction): string {
    const payload = Buffer.from(
      JSON.stringify({ tx, exp: this.clock.now().getTime() + TX_TTL_MS }),
    ).toString('base64url');
    return `${payload}.${this.mac(payload)}`;
  }

  private verify(value: string): EntraTransaction | null {
    const [payload, mac] = value.split('.');
    if (!payload || !mac) return null;
    const expected = Buffer.from(this.mac(payload));
    const actual = Buffer.from(mac);
    if (expected.length !== actual.length || !timingSafeEqual(expected, actual)) return null;
    try {
      const parsed = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8')) as {
        tx: EntraTransaction;
        exp: number;
      };
      return parsed.exp > this.clock.now().getTime() ? parsed.tx : null;
    } catch {
      return null;
    }
  }

  private mac(payload: string): string {
    return createHmac('sha256', this.env.SESSION_SECRET).update(payload).digest('base64url');
  }
}
