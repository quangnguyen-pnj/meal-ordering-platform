import type { EntraClaims } from '@meal/shared';

export const ENTRA_PROVIDER = Symbol('ENTRA_PROVIDER');

/** Data the callback needs back from the browser, kept in a signed short-lived cookie. */
export interface EntraTransaction {
  state: string;
  nonce: string;
  codeVerifier: string;
}

export interface EntraStart {
  /** Where to send the browser. */
  redirectUrl: string;
  transaction: EntraTransaction;
}

/**
 * Entra ID sign-in (plan section 5). Two implementations: the real OIDC Authorization Code +
 * PKCE flow, and a mock for development until the app registration exists.
 */
export interface EntraProvider {
  readonly mode: 'oidc' | 'mock';
  start(): Promise<EntraStart>;
  /** Validates the callback and returns the id_token claims. Throws on any failure. */
  complete(query: Record<string, string | undefined>, tx: EntraTransaction): Promise<EntraClaims>;
}
