import { loadEnv } from './env';

const base = {
  DATABASE_URL: 'postgresql://x:x@localhost:5432/x',
  SESSION_SECRET: 'a-very-long-secret-value-0123456789abcdef',
};

describe('loadEnv', () => {
  it('applies development defaults', () => {
    const env = loadEnv(base);
    expect(env).toMatchObject({
      NODE_ENV: 'development',
      ENTRA_MODE: 'mock',
      DMP_MODE: 'mock',
      CLOCK_FAKE_ENABLED: false,
      COOKIE_SECURE: false,
      API_PORT: 3001,
    });
  });

  it('treats empty strings from .env files as unset', () => {
    expect(loadEnv({ ...base, ENTRA_TENANT_ID: '' }).ENTRA_TENANT_ID).toBeUndefined();
  });

  it('requires a long session secret', () => {
    expect(() => loadEnv({ ...base, SESSION_SECRET: 'short' })).toThrow(/SESSION_SECRET/);
  });

  it('requires Entra settings when ENTRA_MODE=oidc', () => {
    expect(() => loadEnv({ ...base, ENTRA_MODE: 'oidc' })).toThrow(/ENTRA_TENANT_ID is required/);
  });

  it('requires DMP credentials when DMP_MODE=http', () => {
    expect(() => loadEnv({ ...base, DMP_MODE: 'http' })).toThrow(/DMP_BASE_URL is required/);
  });

  it('rejects the mock Microsoft page and the example secret in production', () => {
    expect(() =>
      loadEnv({
        ...base,
        NODE_ENV: 'production',
        SESSION_SECRET: 'change-me-dev-only-change-me-dev-only',
      }),
    ).toThrow(
      /ENTRA_MODE=mock is not allowed in production[\s\S]*Replace the example SESSION_SECRET/,
    );
  });

  it('requires the Secure cookie flag, so HTTPS, in production', () => {
    const prod = {
      ...base,
      NODE_ENV: 'production',
      ENTRA_MODE: 'oidc',
      ENTRA_TENANT_ID: 't',
      ENTRA_CLIENT_ID: 'c',
      ENTRA_CLIENT_SECRET: 's',
      ENTRA_REDIRECT_URI: 'https://meal.pnjp.test/api/auth/entra/callback',
      SESSION_SECRET: 'a-real-secret-a-real-secret-a-real-secret',
    };
    expect(() => loadEnv(prod)).toThrow(/COOKIE_SECURE=true is required in production/);
    expect(loadEnv({ ...prod, COOKIE_SECURE: 'true' }).COOKIE_SECURE).toBe(true);
  });
});
