'use client';

import { Search } from 'lucide-react';
import type * as React from 'react';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';

/**
 * A filter's name sits on the field's top-left edge instead of above it, so a row of filters is
 * one field high. The label's lower half takes the field's fill (it follows hover and focus
 * through --lb) and hides the edge under the text; its upper half stays see-through.
 * The label is a real <label for>, so it still names the field for screen readers.
 */
export function FilterField({
  label,
  htmlFor,
  className,
  children,
}: {
  label: string;
  htmlFor: string;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <div
      className={cn(
        'group relative w-full [--lb:var(--field)] hover:[--lb:var(--muted)] has-[:focus-visible]:[--lb:var(--background)]',
        className,
      )}
    >
      {children}
      <label
        htmlFor={htmlFor}
        className="pointer-events-none absolute -top-2 left-2 max-w-[calc(100%-1rem)] truncate rounded-sm bg-[linear-gradient(to_bottom,transparent_50%,hsl(var(--lb))_50%)] px-1 text-xs font-medium leading-4 text-muted-foreground group-has-[:disabled]:opacity-50"
      >
        {label}
      </label>
    </div>
  );
}

/** The search box of a filter row: a magnifier and a hint inside the box, no label above it. */
export function SearchField({
  id,
  label,
  value,
  onChange,
  className,
}: {
  id?: string;
  /** Shown as the hint and read out as the field's name. */
  label: string;
  value: string;
  onChange: (value: string) => void;
  className?: string;
}) {
  return (
    <div className={cn('relative w-full', className)}>
      <Search
        className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground"
        aria-hidden
      />
      <Input
        id={id}
        type="search"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={label}
        aria-label={label}
        className="pl-9"
      />
    </div>
  );
}
