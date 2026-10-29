'use client';

import type { MasterItemView } from '@/lib/api-types';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { toast } from 'sonner';
import { Alert } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { AppDialog } from '@/components/app-dialog';
import { Input } from '@/components/ui/input';
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
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { usePagination } from '@/hooks/use-pagination';
import { api } from '@/lib/api';
import { apiErrorText } from '@/lib/errors';
import { t, vi } from '@/messages';

type Kind = 'canteens' | 'floors' | 'cost-centers';
const TAB_LABEL: Record<Kind, string> = {
  canteens: vi.master.tabs.canteens,
  floors: vi.master.tabs.floors,
  'cost-centers': vi.master.tabs.costCenters,
};
const ADD_LABEL: Record<Kind, string> = {
  canteens: vi.master.add.canteens,
  floors: vi.master.add.floors,
  'cost-centers': vi.master.add.costCenters,
};
const EDIT_LABEL: Record<Kind, string> = {
  canteens: vi.master.edit.canteens,
  floors: vi.master.edit.floors,
  'cost-centers': vi.master.edit.costCenters,
};

/** /admin/master (BR-62): canteens, floors, cost centers; add, edit, deactivate. */
export function MasterScreen() {
  const [kind, setKind] = useState<Kind>('canteens');
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('master.title')}</CardTitle>
        <CardDescription>{t('master.intro')}</CardDescription>
      </CardHeader>
      <CardContent>
        <Tabs value={kind} onValueChange={(v) => setKind(v as Kind)}>
          <TabsList aria-label={t('master.title')}>
            {(Object.keys(TAB_LABEL) as Kind[]).map((key) => (
              <TabsTrigger key={key} value={key}>
                {TAB_LABEL[key]}
              </TabsTrigger>
            ))}
          </TabsList>
          {(Object.keys(TAB_LABEL) as Kind[]).map((key) => (
            <TabsContent key={key} value={key}>
              <MasterTable kind={key} />
            </TabsContent>
          ))}
        </Tabs>
      </CardContent>
    </Card>
  );
}

interface Draft {
  id: string | null;
  code: string;
  name: string;
  sortOrder: number;
}

function MasterTable({ kind }: { kind: Kind }) {
  const qc = useQueryClient();
  const path = `/admin/master/${kind}`;
  const list = useQuery({
    queryKey: [path],
    queryFn: () => api.get<{ items: MasterItemView[] }>(path),
  });
  const [draft, setDraft] = useState<Draft | null>(null);
  const { pageItems, pagination } = usePagination(list.data?.items ?? []);
  const isFloor = kind === 'floors';
  const refresh = () => void qc.invalidateQueries({ queryKey: [path] });

  const save = useMutation({
    mutationFn: (d: Draft) =>
      d.id
        ? api.patch(
            `${path}/${d.id}`,
            isFloor ? { name: d.name, sortOrder: d.sortOrder } : { name: d.name },
          )
        : api.post(
            path,
            isFloor
              ? { code: d.code, name: d.name, sortOrder: d.sortOrder }
              : { code: d.code, name: d.name },
          ),
    onSuccess: () => {
      setDraft(null);
      toast.success(t('common.saved'));
      refresh();
    },
  });
  const toggle = useMutation({
    mutationFn: (item: MasterItemView) => api.patch(`${path}/${item.id}`, { active: !item.active }),
    onSuccess: (_res, item) => {
      toast.success(
        t(item.active ? 'master.deactivated' : 'master.activated', { name: item.name }),
      );
      refresh();
    },
  });
  const error = toggle.error ?? list.error;
  const open = (d: Draft) => {
    save.reset();
    setDraft(d);
  };

  return (
    <div className="flex flex-col gap-5">
      <div className="flex justify-end">
        <Button onClick={() => open({ id: null, code: '', name: '', sortOrder: 0 })}>
          {ADD_LABEL[kind]}
        </Button>
      </div>
      <AppDialog
        open={draft !== null}
        onClose={() => setDraft(null)}
        title={draft?.id ? EDIT_LABEL[kind] : ADD_LABEL[kind]}
      >
        {draft ? (
          <form
            className="flex flex-col gap-5"
            onSubmit={(e) => {
              e.preventDefault();
              save.mutate(draft);
            }}
          >
            <div className="flex flex-col gap-2">
              <Label htmlFor="master-code">{t('common.code')}</Label>
              <Input
                id="master-code"
                required
                disabled={draft.id !== null}
                maxLength={32}
                aria-describedby="master-code-hint"
                value={draft.code}
                onChange={(e) => setDraft({ ...draft, code: e.target.value })}
              />
              <p id="master-code-hint" className="text-xs text-muted-foreground">
                {t('master.codeHint')}
              </p>
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="master-name">{t('common.name')}</Label>
              <Input
                id="master-name"
                required
                maxLength={120}
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
              />
            </div>
            {isFloor ? (
              <div className="flex w-28 flex-col gap-2">
                <Label htmlFor="master-order">{t('master.sortOrder')}</Label>
                <Input
                  id="master-order"
                  type="number"
                  min={0}
                  value={draft.sortOrder}
                  onChange={(e) => setDraft({ ...draft, sortOrder: Number(e.target.value) })}
                />
              </div>
            ) : null}
            {save.error ? <Alert variant="destructive">{apiErrorText(save.error)}</Alert> : null}
            <div className="flex flex-col-reverse gap-2 pt-2 sm:flex-row sm:justify-end">
              <Button type="button" variant="outline" onClick={() => setDraft(null)}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" disabled={save.isPending}>
                {save.isPending ? t('common.saving') : t('common.save')}
              </Button>
            </div>
          </form>
        ) : null}
      </AppDialog>
      {error ? <Alert variant="destructive">{apiErrorText(error)}</Alert> : null}
      {list.isLoading ? <LoadingRows rows={3} /> : null}
      <Table containerClassName={list.isLoading ? 'hidden' : undefined} className="min-w-[36rem]">
        <TableHeader>
          <TableRow>
            <TableHead>{t('common.code')}</TableHead>
            <TableHead>{t('common.name')}</TableHead>
            {isFloor ? <TableHead>{t('master.sortOrder')}</TableHead> : null}
            {kind === 'cost-centers' ? <TableHead>{t('master.source')}</TableHead> : null}
            <TableHead>{t('master.usage')}</TableHead>
            <TableHead>{t('common.status')}</TableHead>
            <TableHead className="text-right">{t('common.actions')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {pageItems.map((item) => (
            <TableRow key={item.id} className={item.active ? undefined : 'text-muted-foreground'}>
              <TableCell className="font-mono">{item.code}</TableCell>
              <TableCell>{item.name}</TableCell>
              {isFloor ? <TableCell>{item.sortOrder}</TableCell> : null}
              {kind === 'cost-centers' ? (
                <TableCell>{item.source ? vi.master.sources[item.source] : null}</TableCell>
              ) : null}
              <TableCell>{item.usageCount}</TableCell>
              <TableCell>
                <Badge variant={item.active ? 'success' : 'outline'}>
                  {item.active ? t('common.active') : t('common.inactive')}
                </Badge>
              </TableCell>
              <TableCell className="text-right">
                <div className="flex justify-end gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() =>
                      open({
                        id: item.id,
                        code: item.code,
                        name: item.name,
                        sortOrder: item.sortOrder ?? 0,
                      })
                    }
                  >
                    {t('common.edit')}
                  </Button>
                  <Button variant="ghost" size="sm" onClick={() => toggle.mutate(item)}>
                    {item.active ? t('common.deactivate') : t('common.activate')}
                  </Button>
                </div>
              </TableCell>
            </TableRow>
          ))}
          {list.data && list.data.items.length === 0 ? (
            <TableRow>
              <TableCell colSpan={7} className="text-muted-foreground">
                {t('master.empty')}
              </TableCell>
            </TableRow>
          ) : null}
        </TableBody>
      </Table>
      <Pagination {...pagination} />
    </div>
  );
}
