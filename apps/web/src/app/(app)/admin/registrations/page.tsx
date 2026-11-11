import { ScreenGuard } from '@/components/screen-guard';
import { RegistrationsAdminScreen } from '@/components/screens/registrations-admin-screen';
import { rolesCanSee } from '@/lib/nav';
import { getSession } from '@/lib/server-api';

export default async function RegistrationsAdminPage() {
  const user = await getSession();
  return (
    <ScreenGuard href="/admin/registrations">
      {/* Only shows the forecast tab; the API checks export.finalAndForecast itself. */}
      <RegistrationsAdminScreen
        canForecast={!!user && rolesCanSee(user.roles, 'export.finalAndForecast')}
      />
    </ScreenGuard>
  );
}
