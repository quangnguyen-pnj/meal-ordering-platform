import { randomBytes } from 'node:crypto';
import type { EntraClaims } from '@meal/shared';
import type { EntraProvider, EntraStart, EntraTransaction } from './entra.provider';

/**
 * Stand-in for Microsoft while no app registration exists. "Authorizing" opens a tiny local page
 * where a developer types an email; the callback then treats it as the id_token email claim.
 * Rejected at startup in production (see env validation).
 */
export class MockEntraProvider implements EntraProvider {
  readonly mode = 'mock' as const;

  async start(): Promise<EntraStart> {
    const state = randomBytes(16).toString('base64url');
    return {
      redirectUrl: `/api/auth/entra/mock-authorize?state=${encodeURIComponent(state)}`,
      transaction: { state, nonce: 'mock', codeVerifier: 'mock' },
    };
  }

  async complete(
    query: Record<string, string | undefined>,
    tx: EntraTransaction,
  ): Promise<EntraClaims> {
    if (!query.state || query.state !== tx.state) throw new Error('State mismatch');
    if (!query.mock_email) throw new Error('mock_email is required');
    return { email: query.mock_email };
  }
}
