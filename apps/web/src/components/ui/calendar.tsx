'use client';

import { ChevronLeft, ChevronRight } from 'lucide-react';
import * as React from 'react';
import { DayPicker } from 'react-day-picker';
import { vi } from 'react-day-picker/locale';
import { buttonVariants } from '@/components/ui/button';
import { cn } from '@/lib/utils';

/** shadcn/ui Calendar (react-day-picker v9), Vietnamese, weeks start on Monday. */
function Calendar({
  className,
  classNames,
  showOutsideDays = true,
  ...props
}: React.ComponentProps<typeof DayPicker>) {
  return (
    <DayPicker
      locale={vi}
      weekStartsOn={1}
      showOutsideDays={showOutsideDays}
      className={cn('p-1', className)}
      classNames={{
        months: 'relative flex flex-col gap-4',
        month: 'flex flex-col gap-3',
        month_caption: 'flex h-9 items-center justify-center',
        caption_label: 'text-sm font-medium capitalize',
        nav: 'absolute inset-x-0 top-0 flex h-9 items-center justify-between',
        button_previous: cn(buttonVariants({ variant: 'outline' }), 'h-9 w-9 p-0'),
        button_next: cn(buttonVariants({ variant: 'outline' }), 'h-9 w-9 p-0'),
        month_grid: 'w-full border-collapse',
        weekdays: 'flex',
        weekday: 'w-10 text-center text-xs font-normal text-muted-foreground',
        week: 'mt-1 flex w-full',
        day: 'h-10 w-10 p-0 text-center text-sm',
        day_button: cn(
          buttonVariants({ variant: 'ghost' }),
          'h-10 w-10 p-0 font-normal aria-selected:opacity-100',
        ),
        selected:
          '[&>button]:bg-primary [&>button]:text-primary-foreground [&>button:hover]:bg-primary [&>button:hover]:text-primary-foreground',
        today: '[&>button]:font-semibold [&>button]:shadow-[inset_0_0_0_1px_hsl(var(--ring))]',
        outside: 'text-muted-foreground opacity-50',
        disabled: 'text-muted-foreground opacity-50',
        hidden: 'invisible',
        ...classNames,
      }}
      components={{
        Chevron: ({ orientation }) =>
          orientation === 'left' ? (
            <ChevronLeft className="h-4 w-4" />
          ) : (
            <ChevronRight className="h-4 w-4" />
          ),
      }}
      {...props}
    />
  );
}
Calendar.displayName = 'Calendar';

export { Calendar };
