import { z } from 'zod';

export const ENV = Symbol('ENV');

const bool = (fallback: boolean) =>
  z
    .enum(['true', 'false', '1', '0'])
    .optional()
    .transform((v) => (v === undefined ? fallback : v === 'true' || v === '1'));

const emptyToUndefined = (v: unknown) => (typeof v === 'string' && v.trim() === '' ? undefined : v);
const optionalString = z.preprocess(emptyToUndefined, z.string().optional());

const EnvSchema = z
  .object({
    NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
    DATABASE_URL: z.string().min(1),
    API_PORT: z.coerce.number().int().positive().default(3001),
    APP_BASE_URL: z.string().url().default('http://localhost:8080'),
    SESSION_SECRET: z.string().min(32, 'SESSION_SECRET must be at least 32 characters'),
    SESSION_TTL_HOURS: z.coerce.number().positive().default(12),
    COOKIE_SECURE: bool(false),
    CLOCK_FAKE_ENABLED: bool(false),
    /** pg-boss background jobs (plan 6.5). Tests turn them off and call the handlers directly. */
    JOBS_ENABLED: bool(true),
    /** Employee code that is made SYS_ADMIN on startup (plan section 3: the first system admin). */
    FIRST_SYSTEM_ADMIN_CODE: optionalString,
    ENTRA_MODE: z.enum(['mock', 'oidc']).default('mock'),
    ENTRA_TENANT_ID: optionalString,
    ENTRA_CLIENT_ID: optionalString,
    ENTRA_CLIENT_SECRET: optionalString,
    ENTRA_REDIRECT_URI: optionalString,
    DMP_MODE: z.enum(['mock', 'http']).default('mock'),
    DMP_MOCK_SCENARIO: z.string().default('on-time'),
    DMP_BASE_URL: optionalString,
    DMP_SERVICE_EMAIL: optionalString,
    DMP_SERVICE_PASSWORD: optionalString,
    DMP_COMPANY_CODE: optionalString,
  })
  .superRefine((env, ctx) => {
    const fail = (path: string, message: string) =>
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: [path], message });

    if (env.ENTRA_MODE === 'oidc') {
      for (const key of [
        'ENTRA_TENANT_ID',
        'ENTRA_CLIENT_ID',
        'ENTRA_CLIENT_SECRET',
        'ENTRA_REDIRECT_URI',
      ] as const) {
        if (!env[key]) fail(key, `${key} is required when ENTRA_MODE=oidc`);
      }
    }
    if (env.DMP_MODE === 'http') {
      for (const key of [
        'DMP_BASE_URL',
        'DMP_SERVICE_EMAIL',
        'DMP_SERVICE_PASSWORD',
        'DMP_COMPANY_CODE',
      ] as const) {
        if (!env[key]) fail(key, `${key} is required when DMP_MODE=http`);
      }
    }
    if (env.NODE_ENV === 'production') {
      // Plan 6.1 / task: fake time and the fake Microsoft page must never run in production.
      if (env.CLOCK_FAKE_ENABLED)
        fail('CLOCK_FAKE_ENABLED', 'Fake time is not allowed in production');
      if (env.ENTRA_MODE === 'mock')
        fail('ENTRA_MODE', 'ENTRA_MODE=mock is not allowed in production');
      if (env.SESSION_SECRET.startsWith('change-me')) {
        fail('SESSION_SECRET', 'Replace the example SESSION_SECRET in production');
      }
      // The PIN and the session cookie travel in clear without HTTPS (security review 2026-10-24).
      if (!env.COOKIE_SECURE)
        fail('COOKIE_SECURE', 'COOKIE_SECURE=true is required in production (serve over HTTPS)');
    }
  });

export type AppEnv = z.infer<typeof EnvSchema>;

export function loadEnv(source: Record<string, string | undefined> = process.env): AppEnv {
  const parsed = EnvSchema.safeParse(source);
  if (!parsed.success) {
    const lines = parsed.error.issues.map((i) => `  - ${i.path.join('.')}: ${i.message}`);
    throw new Error(`Invalid environment configuration:\n${lines.join('\n')}`);
  }
  return parsed.data;
}
