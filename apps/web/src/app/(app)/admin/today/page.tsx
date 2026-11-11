import { ScreenGuard } from '@/components/screen-guard';
import { AdminTodayScreen } from '@/components/screens/admin-today-screen';
import { rolesCanSee } from '@/lib/nav';
import { getSession } from '@/lib/server-api';

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

const one = (v: string | string[] | undefined) => (typeof v === 'string' ? v : '');

/** `?date=YYYY-MM-DD&q=…` from the reconciliation cases; anything else opens today, unfiltered. */
export default async function AdminTodayPage({ searchParams }: { searchParams: SearchParams }) {
  const [user, params] = await Promise.all([getSession(), searchParams]);
  const date = one(params.date);
  return (
    <ScreenGuard href="/admin/today">
      {/* Only shows the re-run button; the API checks sync.manage itself. */}
      <AdminTodayScreen
        canSync={!!user && rolesCanSee(user.roles, 'sync.manage')}
        initialDate={/^\d{4}-\d{2}-\d{2}$/.test(date) ? date : ''}
        initialQuery={one(params.q).trim().slice(0, 50)}
      />
    </ScreenGuard>
  );
}
