'use client';

import { CalendarDays } from 'lucide-react';
import * as React from 'react';
import { Button } from '@/components/ui/button';
import { Calendar } from '@/components/ui/calendar';
import { fieldSurface } from '@/components/ui/field';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { cn, formatDate } from '@/lib/utils';

/** YYYY-MM-DD to a local-midnight Date (what the calendar compares), without reading the clock. */
export function parseIsoDate(iso: string): Date | undefined {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  if (!m) return undefined;
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  return Number.isNaN(d.getTime()) ? undefined : d;
}

/** The calendar day the user clicked, as YYYY-MM-DD (local fields, so no timezone shift). */
export function toIsoDate(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

type DatePickerProps = {
  id?: string;
  /** YYYY-MM-DD, or '' for none. */
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  className?: string;
  disabled?: boolean;
  /** Latest pickable day, YYYY-MM-DD: later days are greyed out and the calendar cannot page past it. */
  max?: string;
};

/**
 * A date field that opens a calendar in a popover (shadcn/ui pattern), instead of the browser's
 * own `<input type="date">`. Value and result are YYYY-MM-DD, shown as dd/MM/yyyy.
 */
function DatePicker({
  id,
  value,
  onChange,
  placeholder = 'dd/mm/yyyy',
  className,
  disabled,
  max,
}: DatePickerProps) {
  const [open, setOpen] = React.useState(false);
  const selected = parseIsoDate(value);
  const maxDate = max ? parseIsoDate(max) : undefined;

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          id={id}
          type="button"
          variant="ghost"
          disabled={disabled}
          className={cn(
            fieldSurface,
            'h-11 w-full justify-start px-3 text-left text-base font-normal md:text-sm',
            !selected && 'text-muted-foreground',
            className,
          )}
        >
          <CalendarDays className="h-4 w-4 text-muted-foreground" />
          {selected ? formatDate(value) : placeholder}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-auto p-3">
        <Calendar
          mode="single"
          selected={selected}
          defaultMonth={selected}
          disabled={maxDate ? { after: maxDate } : undefined}
          endMonth={maxDate}
          onSelect={(day) => {
            // Clicking the selected day again clears it in the library; keep the date instead.
            if (!day) return;
            onChange(toIsoDate(day));
            setOpen(false);
          }}
          autoFocus
        />
      </PopoverContent>
    </Popover>
  );
}

export { DatePicker };
