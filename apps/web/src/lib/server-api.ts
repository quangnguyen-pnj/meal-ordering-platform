import { SessionUserSchema } from '@meal/shared';
import type { SessionUser } from '@/lib/api-types';
import { cookies } from 'next/headers';
import { z } from 'zod';

export const SESSION_COOKIE = 'meal_session';

const MeSchema = z.object({ user: SessionUserSchema });

/** Where the Next.js server reaches the API (not the browser-facing /api). */
function apiBase(): string {
  return process.env.API_INTERNAL_URL ?? 'http://localhost:3001';
}

/** The signed-in user for this request, or null. Server components and layouts only. */
export async function getSession(): Promise<SessionUser | null> {
  const store = await cookies();
  const token = store.get(SESSION_COOKIE)?.value;
  if (!token) return null;
  try {
    const res = await fetch(`${apiBase()}/api/auth/me`, {
      headers: { cookie: `${SESSION_COOKIE}=${token}` },
      cache: 'no-store',
    });
    if (!res.ok) return null;
    const parsed = MeSchema.safeParse(await res.json());
    return parsed.success ? parsed.data.user : null;
  } catch {
    return null;
  }
}

/** GET an API path with the caller's session, for server components. Null on any failure. */
export async function serverGet<T>(path: string): Promise<T | null> {
  const store = await cookies();
  const token = store.get(SESSION_COOKIE)?.value;
  if (!token) return null;
  try {
    const res = await fetch(`${apiBase()}/api${path}`, {
      headers: { cookie: `${SESSION_COOKIE}=${token}` },
      cache: 'no-store',
    });
    return res.ok ? ((await res.json()) as T) : null;
  } catch {
    return null;
  }
}
