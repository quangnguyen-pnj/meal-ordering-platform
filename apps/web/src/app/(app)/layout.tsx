import type { BannerView } from '@/lib/api-types';
import { redirect } from 'next/navigation';
import { AppShell } from '@/components/app-shell/app-shell';
import { getSession, serverGet } from '@/lib/server-api';

export const dynamic = 'force-dynamic';

export default async function AppLayout({ children }: { children: React.ReactNode }) {
  const user = await getSession();
  if (!user) redirect('/login');
  if (user.pinMustChange) redirect('/change-pin');

  // BR-06 and BR-14 now; attendance sync failed (P3) and day not finalized (P4) join later.
  const banners = await serverGet<{ items: BannerView[] }>('/me/banners');

  return (
    <AppShell user={user} banners={banners?.items ?? []}>
      {children}
    </AppShell>
  );
}
