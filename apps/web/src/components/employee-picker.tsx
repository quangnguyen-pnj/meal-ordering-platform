'use client';

import type { EmployeeMappingView } from '@/lib/api-types';
import { useQuery } from '@tanstack/react-query';
import { ChevronsUpDown, X } from 'lucide-react';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/components/ui/command';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Spinner } from '@/components/ui/skeleton';
import { useDebounced } from '@/hooks/use-debounced';
import { api } from '@/lib/api';
import { cn } from '@/lib/utils';
import { t } from '@/messages';

export type PickedEmployee = Pick<EmployeeMappingView, 'employeeCode' | 'name' | 'orgPath'>;

/**
 * Employee combobox (shadcn/ui Popover + Command), so an admin sees the name before granting a
 * role instead of trusting a typed code. Searches the caller's own scope through /employees.
 */
export function EmployeePicker({
  id,
  value,
  onChange,
  placeholder = t('employeePicker.placeholder'),
}: {
  id?: string;
  value: PickedEmployee | null;
  onChange: (employee: PickedEmployee | null) => void;
  placeholder?: string;
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const term = useDebounced(q.trim(), 250);

  const search = useQuery({
    queryKey: ['/employees', 'picker', term],
    queryFn: () =>
      api.get<{ items: EmployeeMappingView[] }>(
        `/employees?${new URLSearchParams({ q: term, status: 'ACTIVE', limit: '8', offset: '0' })}`,
      ),
    enabled: open && term.length >= 2,
  });
  const items = term.length >= 2 ? (search.data?.items ?? []) : [];

  return (
    <div className="flex w-full items-center gap-1">
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            id={id}
            type="button"
            variant="outline"
            role="combobox"
            aria-expanded={open}
            className={cn(
              'h-auto min-h-11 w-full justify-between px-3 py-1.5 text-left font-normal',
              !value && 'text-muted-foreground',
            )}
          >
            {value ? (
              <span className="min-w-0">
                <span className="block truncate font-medium text-foreground">{value.name}</span>
                <span className="block truncate text-xs text-muted-foreground">
                  <span className="font-mono">{value.employeeCode}</span> · {value.orgPath}
                </span>
              </span>
            ) : (
              <span>{placeholder}</span>
            )}
            <ChevronsUpDown className="h-4 w-4 opacity-50" aria-hidden />
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-[--radix-popover-trigger-width] min-w-72 p-0" align="start">
          <Command shouldFilter={false}>
            <CommandInput placeholder={t('employeePicker.search')} value={q} onValueChange={setQ} />
            <CommandList>
              {search.isFetching ? (
                <div className="flex justify-center py-4">
                  <Spinner className="text-muted-foreground" />
                </div>
              ) : null}
              {!search.isFetching && term.length >= 2 ? (
                <CommandEmpty>
                  {search.error ? t('employeePicker.error') : t('employeePicker.noResult')}
                </CommandEmpty>
              ) : null}
              {term.length < 2 ? (
                <p className="px-3 py-4 text-center text-sm text-muted-foreground">
                  {t('employeePicker.hint')}
                </p>
              ) : null}
              {items.length > 0 ? (
                <CommandGroup>
                  {items.map((e) => (
                    <CommandItem
                      key={e.employeeCode}
                      value={e.employeeCode}
                      onSelect={() => {
                        onChange({
                          employeeCode: e.employeeCode,
                          name: e.name,
                          orgPath: e.orgPath,
                        });
                        setQ('');
                        setOpen(false);
                      }}
                    >
                      <span className="min-w-0">
                        <span className="block font-medium">
                          {e.name}{' '}
                          <span className="font-mono text-xs text-muted-foreground">
                            {e.employeeCode}
                          </span>
                        </span>
                        <span className="block truncate text-xs text-muted-foreground">
                          {e.orgPath}
                        </span>
                      </span>
                    </CommandItem>
                  ))}
                </CommandGroup>
              ) : null}
            </CommandList>
          </Command>
        </PopoverContent>
      </Popover>
      {value ? (
        <Button
          type="button"
          variant="ghost"
          size="icon"
          aria-label={t('employeePicker.clear')}
          onClick={() => onChange(null)}
        >
          <X className="h-4 w-4" />
        </Button>
      ) : null}
    </div>
  );
}
