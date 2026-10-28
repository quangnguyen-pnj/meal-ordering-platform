import type * as ClientLib from 'openid-client';
import type { EntraClaims } from '@meal/shared';
import type { AppEnv } from '../../config/env';
import type { EntraProvider, EntraStart, EntraTransaction } from './entra.provider';

/**
 * Real Entra ID flow: OIDC Authorization Code + PKCE (plan section 6.1).
 *
 * TODO: untested against a real tenant. The Entra app registration is an external dependency
 * (plan section 8, before P7); until then ENTRA_MODE=mock is used.
 */
export class OidcEntraProvider implements EntraProvider {
  readonly mode = 'oidc' as const;
  private config: ClientLib.Configuration | null = null;

  constructor(private readonly env: AppEnv) {}

  /**
   * openid-client v6 is ESM-only. A dynamic import is compiled to require() of an ES module,
   * which Node >= 22.12 supports, and keeps the library out of mock-mode runs and unit tests.
   */
  private lib(): Promise<typeof ClientLib> {
    return import('openid-client');
  }

  private async getConfig(): Promise<ClientLib.Configuration> {
    if (!this.config) {
      const client = await this.lib();
      const issuer = new URL(`https://login.microsoftonline.com/${this.env.ENTRA_TENANT_ID}/v2.0`);
      this.config = await client.discovery(
        issuer,
        this.env.ENTRA_CLIENT_ID ?? '',
        this.env.ENTRA_CLIENT_SECRET,
      );
    }
    return this.config;
  }

  async start(): Promise<EntraStart> {
    const client = await this.lib();
    const config = await this.getConfig();
    const codeVerifier = client.randomPKCECodeVerifier();
    const state = client.randomState();
    const nonce = client.randomNonce();
    const url = client.buildAuthorizationUrl(config, {
      redirect_uri: this.env.ENTRA_REDIRECT_URI ?? '',
      scope: 'openid profile email',
      code_challenge: await client.calculatePKCECodeChallenge(codeVerifier),
      code_challenge_method: 'S256',
      state,
      nonce,
    });
    return { redirectUrl: url.href, transaction: { state, nonce, codeVerifier } };
  }

  async complete(
    query: Record<string, string | undefined>,
    tx: EntraTransaction,
  ): Promise<EntraClaims> {
    const client = await this.lib();
    const config = await this.getConfig();
    // Rebuild the callback URL from the registered redirect URI so proxies cannot skew the host.
    const current = new URL(this.env.ENTRA_REDIRECT_URI ?? '');
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined) current.searchParams.set(key, value);
    }
    const tokens = await client.authorizationCodeGrant(config, current, {
      pkceCodeVerifier: tx.codeVerifier,
      expectedState: tx.state,
      expectedNonce: tx.nonce,
      idTokenExpected: true,
    });
    const claims = tokens.claims();
    if (!claims) throw new Error('Entra response contained no id_token claims');
    return claims as EntraClaims;
  }
}
