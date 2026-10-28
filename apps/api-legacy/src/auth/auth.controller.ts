import {
  Body,
  Controller,
  Get,
  HttpCode,
  HttpStatus,
  NotFoundException,
  Post,
  Query,
  Req,
  Res,
} from '@nestjs/common';
import {
  ChangePinSchema,
  PinLoginSchema,
  type ChangePinInput,
  type PinLoginInput,
} from '@meal/shared';
import type { Request, Response } from 'express';
import { ZodValidationPipe } from '../common/zod-validation.pipe';
import { AllowPinChange, Authenticated, CurrentPrincipal, Public } from '../rbac/decorators';
import { PrincipalService } from '../rbac/principal.service';
import type { RequestPrincipal } from '../rbac/rbac.types';
import { EntraAuthService, OIDC_COOKIE } from './entra-auth.service';
import { PinAuthService } from './pin-auth.service';
import { SessionService } from './session.service';

@Controller('auth')
export class AuthController {
  constructor(
    private readonly pinAuth: PinAuthService,
    private readonly entra: EntraAuthService,
    private readonly sessions: SessionService,
    private readonly principals: PrincipalService,
  ) {}

  @Public()
  @Post('pin/login')
  @HttpCode(HttpStatus.OK)
  async pinLogin(
    @Body(new ZodValidationPipe(PinLoginSchema)) body: PinLoginInput,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ) {
    const result = await this.pinAuth.login(body.employeeCode, body.pin, req.get('user-agent'));
    this.sessions.setCookie(res, result.token, result.expiresAt);
    const principal = await this.principals.load(result.employeeId, {
      id: result.sessionId,
      method: 'PIN',
    });
    return { user: principal ? this.principals.toSessionUser(principal) : null };
  }

  @Authenticated()
  @AllowPinChange()
  @Post('pin/change')
  @HttpCode(HttpStatus.NO_CONTENT)
  async changePin(
    @Body(new ZodValidationPipe(ChangePinSchema)) body: ChangePinInput,
    @CurrentPrincipal() principal: RequestPrincipal,
  ): Promise<void> {
    await this.pinAuth.changePin(
      principal.employeeId,
      principal.sessionId,
      body.currentPin,
      body.newPin,
    );
  }

  @Authenticated()
  @AllowPinChange()
  @Post('logout')
  @HttpCode(HttpStatus.NO_CONTENT)
  async logout(
    @CurrentPrincipal() principal: RequestPrincipal,
    @Res({ passthrough: true }) res: Response,
  ): Promise<void> {
    await this.sessions.revoke(principal.sessionId);
    this.sessions.clearCookie(res);
  }

  @Authenticated()
  @AllowPinChange()
  @Get('me')
  me(@CurrentPrincipal() principal: RequestPrincipal) {
    return { user: this.principals.toSessionUser(principal) };
  }

  // --- Entra ID SSO (plan section 5) ---------------------------------------------------------

  @Public()
  @Get('entra/login')
  async entraLogin(@Res() res: Response): Promise<void> {
    const { redirectUrl, cookieValue } = await this.entra.start();
    res.cookie(OIDC_COOKIE, cookieValue, {
      httpOnly: true,
      sameSite: 'lax', // the return from Microsoft is a cross-site top-level GET
      secure: this.sessions.secureCookies,
      path: '/api/auth/entra',
      maxAge: 10 * 60_000,
    });
    res.redirect(redirectUrl);
  }

  @Public()
  @Get('entra/callback')
  async entraCallback(
    @Query() query: Record<string, string | undefined>,
    @Req() req: Request,
    @Res() res: Response,
  ): Promise<void> {
    const cookies = req.cookies as Record<string, string | undefined> | undefined;
    const result = await this.entra.complete(query, cookies?.[OIDC_COOKIE], req.get('user-agent'));
    res.clearCookie(OIDC_COOKIE, { path: '/api/auth/entra' });
    if (!result.ok) {
      res.redirect(result.reason === 'not-linked' ? '/auth/not-linked' : '/login?error=sso_failed');
      return;
    }
    this.sessions.setCookie(res, result.token, result.expiresAt);
    res.redirect('/today');
  }

  /** The fake "Microsoft" page. Exists only in ENTRA_MODE=mock; 404 otherwise. */
  @Public()
  @Get('entra/mock-authorize')
  mockAuthorize(@Query('state') state: string | undefined, @Res() res: Response): void {
    if (this.entra.provider.mode !== 'mock') throw new NotFoundException();
    const safeState = (state ?? '').replace(/[^A-Za-z0-9_-]/g, '');
    res.type('html').send(`<!doctype html>
<html lang="vi"><head><meta charset="utf-8"><title>Mock Microsoft sign-in</title></head>
<body style="font-family:sans-serif;max-width:360px;margin:4rem auto">
<h1>Mock Microsoft sign-in</h1>
<p>Development only. Enter the email of the employee to sign in as.</p>
<form method="get" action="/api/auth/entra/callback">
<input type="hidden" name="state" value="${safeState}">
<input type="email" name="mock_email" placeholder="an.nguyen@pnjp.test" required style="width:100%;padding:.5rem">
<button type="submit" style="margin-top:1rem;padding:.5rem 1rem">Sign in</button>
</form></body></html>`);
  }
}
