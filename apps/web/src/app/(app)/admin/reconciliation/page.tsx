import { ScreenGuard } from '@/components/screen-guard';
import { ReconciliationScreen } from '@/components/screens/reconciliation-screen';
import { rolesCanSee } from '@/lib/nav';
import { getSession } from '@/lib/server-api';

export default async function ReconciliationPage() {
  const user = await getSession();
  const roles = user?.roles ?? [];
  return (
    <ScreenGuard href="/admin/reconciliation">
      {/* Only shows or hides buttons; the API checks every action itself. */}
      <ReconciliationScreen
        can={{
          finalize: rolesCanSee(roles, 'day.finalize'),
          export: rolesCanSee(roles, 'export.finalAndForecast'),
          adjust: rolesCanSee(roles, 'adjustment.manage'),
          sync: rolesCanSee(roles, 'sync.manage'),
        }}
      />
    </ScreenGuard>
  );
}
