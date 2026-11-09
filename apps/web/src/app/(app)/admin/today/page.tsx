import { ScreenGuard } from '@/components/screen-guard';
import { AdminTodayScreen } from '@/components/screens/admin-today-screen';
import { rolesCanSee } from '@/lib/nav';
import { getSession } from '@/lib/server-api';

export default async function AdminTodayPage() {
  const user = await getSession();
  return (
    <ScreenGuard href="/admin/today">
      {/* Only shows the re-run button; the API checks sync.manage itself. */}
      <AdminTodayScreen canSync={!!user && rolesCanSee(user.roles, 'sync.manage')} />
    </ScreenGuard>
  );
}
