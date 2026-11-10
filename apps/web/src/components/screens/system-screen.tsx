'use client';

import { UserMinus, UserPlus } from 'lucide-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { toast } from 'sonner';
import { EmployeePicker, type PickedEmployee } from '@/components/employee-picker';
import { ResponsiveSelect } from '@/components/responsive-select';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { useConfirm } from '@/components/confirm-dialog';
import { Label } from '@/components/ui/label';
import { Pagination } from '@/components/ui/pagination';
import { LoadingRows } from '@/components/ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { usePagination } from '@/hooks/use-pagination';
import { api } from '@/lib/api';
import { apiErrorText } from '@/lib/errors';
import { formatVnDateTime } from '@/lib/utils';
import { t, vi } from '@/messages';

type PrivilegedRole = 'HC_ADMIN' | 'SYS_ADMIN';

interface RoleRow {
  id: string;
  role: PrivilegedRole;
  employeeCode: string;
  name: string;
  createdAt: string;
}

interface SyncRunRow {
  id: string;
  type: keyof typeof vi.system.syncType;
  status: keyof typeof vi.system.syncStatus;
  startedAt: string;
  recordCount: number | null;
  error: string | null;
}

/** /system: HC and system admin roles (system admins only) and the sync log. */
export function SystemScreen() {
  const qc = useQueryClient();
  const [pick, setPick] = useState<PickedEmployee | null>(null);
  const [confirm, confirmElement] = useConfirm();
  const [role, setRole] = useState<PrivilegedRole>('HC_ADMIN');
  const roles = useQuery({
    queryKey: ['/system/roles'],
    queryFn: () => api.get<{ items: RoleRow[] }>('/system/roles'),
  });
  const runs = useQuery({
    queryKey: ['/system/sync-runs'],
    queryFn: () => api.get<{ items: SyncRunRow[] }>('/system/sync-runs'),
  });
  const runPage = usePagination(runs.data?.items ?? []);
  const assign = useMutation({
    mutationFn: (employeeCode: string) => api.post('/system/roles', { employeeCode, role }),
    onSuccess: () => {
      toast.success(t('system.assigned', { name: pick?.name ?? '', role: vi.roles[role] }));
      setPick(null);
      void qc.invalidateQueries({ queryKey: ['/system/roles'] });
    },
  });
  const revoke = useMutation({
    mutationFn: (id: string) => api.post(`/system/roles/${id}/revoke`),
    onSuccess: () => {
      toast.success(t('system.revoked'));
      void qc.invalidateQueries({ queryKey: ['/system/roles'] });
    },
  });
  const error = roles.error ?? assign.error ?? revoke.error;

  return (
    <div className="flex flex-col gap-5">
      <Card>
        <CardHeader>
          <CardTitle>{t('system.rolesTitle')}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-6">
          <form
            className="flex flex-wrap items-end gap-4"
            onSubmit={(e) => {
              e.preventDefault();
              if (pick) assign.mutate(pick.employeeCode);
            }}
          >
            <div className="flex w-full flex-col gap-2 sm:w-80">
              <Label htmlFor="role-code">{t('employeePicker.label')}</Label>
              <EmployeePicker id="role-code" value={pick} onChange={setPick} />
            </div>
            <div className="flex w-full flex-col gap-2 sm:w-56">
              <Label htmlFor="role-role">{t('system.role')}</Label>
              <ResponsiveSelect
                id="role-role"
                value={role}
                onValueChange={(v) => setRole(v as PrivilegedRole)}
                options={[
                  { value: 'HC_ADMIN', label: vi.roles.HC_ADMIN },
                  { value: 'SYS_ADMIN', label: vi.roles.SYS_ADMIN },
                ]}
              />
            </div>
            <Button type="submit" disabled={!pick || assign.isPending}>
              <UserPlus className="h-4 w-4" aria-hidden />
              {t('system.assign')}
            </Button>
          </form>
          {error ? <Alert variant="destructive">{apiErrorText(error)}</Alert> : null}
          {confirmElement}
          {roles.isLoading ? <LoadingRows rows={2} /> : null}
          <Table
            containerClassName={roles.isLoading ? 'hidden' : undefined}
            className="min-w-[36rem]"
          >
            <TableHeader>
              <TableRow>
                <TableHead>{t('common.code')}</TableHead>
                <TableHead>{t('common.name')}</TableHead>
                <TableHead>{t('system.role')}</TableHead>
                <TableHead>{t('system.since')}</TableHead>
                <TableHead className="text-right">{t('common.actions')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(roles.data?.items ?? []).map((r) => (
                <TableRow key={r.id}>
                  <TableCell className="font-mono">{r.employeeCode}</TableCell>
                  <TableCell>{r.name}</TableCell>
                  <TableCell>{vi.roles[r.role]}</TableCell>
                  <TableCell>{formatVnDateTime(r.createdAt)}</TableCell>
                  <TableCell className="text-right">
                    <Button
                      variant="tertiary"
                      className="text-destructive hover:bg-destructive/10"
                      size="sm"
                      onClick={async () => {
                        const ok = await confirm({
                          title: t('system.revokeTitle'),
                          description: t('system.revokeConfirm', {
                            role: vi.roles[r.role],
                            name: r.name,
                          }),
                          confirmLabel: t('system.revoke'),
                          destructive: true,
                        });
                        if (ok) revoke.mutate(r.id);
                      }}
                    >
                      <UserMinus className="h-4 w-4" aria-hidden />
                      {t('system.revoke')}
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle className="text-lg">{t('system.syncTitle')}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-5">
          {runs.error ? <Alert variant="destructive">{apiErrorText(runs.error)}</Alert> : null}
          {runs.isLoading ? <LoadingRows rows={3} /> : null}
          <Table
            containerClassName={runs.isLoading ? 'hidden' : undefined}
            className="min-w-[36rem]"
          >
            <TableHeader>
              <TableRow>
                <TableHead>{t('system.started')}</TableHead>
                <TableHead>{t('dishes.type')}</TableHead>
                <TableHead>{t('common.status')}</TableHead>
                <TableHead>{t('system.records')}</TableHead>
                <TableHead>{t('system.error')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {runPage.pageItems.map((r) => (
                <TableRow key={r.id}>
                  <TableCell>{formatVnDateTime(r.startedAt)}</TableCell>
                  <TableCell>{vi.system.syncType[r.type]}</TableCell>
                  <TableCell>
                    <Badge
                      variant={
                        r.status === 'SUCCESS'
                          ? 'success'
                          : r.status === 'FAILED'
                            ? 'destructive'
                            : 'outline'
                      }
                    >
                      {vi.system.syncStatus[r.status]}
                    </Badge>
                  </TableCell>
                  <TableCell>{r.recordCount ?? t('common.none')}</TableCell>
                  <TableCell className="max-w-md truncate text-xs" title={r.error ?? undefined}>
                    {r.error ?? ''}
                  </TableCell>
                </TableRow>
              ))}
              {runs.data && runs.data.items.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5} className="text-muted-foreground">
                    {t('system.noSyncRuns')}
                  </TableCell>
                </TableRow>
              ) : null}
            </TableBody>
          </Table>
          <Pagination {...runPage.pagination} />
        </CardContent>
      </Card>
    </div>
  );
}
