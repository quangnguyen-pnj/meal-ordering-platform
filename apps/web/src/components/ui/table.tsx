import * as React from 'react';
import { cn } from '@/lib/utils';

// shadcn/ui Table. The wrapper scrolls sideways; give wide tables a min-width via className.
const Table = React.forwardRef<
  HTMLTableElement,
  React.HTMLAttributes<HTMLTableElement> & { containerClassName?: string }
>(({ className, containerClassName, ...props }, ref) => (
  <div
    className={cn(
      'relative w-full overflow-x-auto rounded-lg bg-card shadow-block',
      containerClassName,
    )}
  >
    <table ref={ref} className={cn('w-full caption-bottom text-sm', className)} {...props} />
  </div>
));
Table.displayName = 'Table';

const TableHeader = React.forwardRef<
  HTMLTableSectionElement,
  React.HTMLAttributes<HTMLTableSectionElement>
>(({ className, ...props }, ref) => (
  <thead ref={ref} className={cn('bg-muted/70 [&_tr]:bg-transparent', className)} {...props} />
));
TableHeader.displayName = 'TableHeader';

/**
 * Rows are told apart by a faint zebra fill, not lines. A dense grid with frozen cells (which
 * paint their own solid background) turns it off with `striped={false}` and draws its own lines.
 */
const TableBody = React.forwardRef<
  HTMLTableSectionElement,
  React.HTMLAttributes<HTMLTableSectionElement> & { striped?: boolean }
>(({ className, striped = true, ...props }, ref) => (
  <tbody
    ref={ref}
    className={cn(
      striped && '[&>tr:nth-child(even)]:bg-muted/30 [&>tr:nth-child(even):hover]:bg-muted/50',
      className,
    )}
    {...props}
  />
));
TableBody.displayName = 'TableBody';

const TableRow = React.forwardRef<HTMLTableRowElement, React.HTMLAttributes<HTMLTableRowElement>>(
  ({ className, ...props }, ref) => (
    <tr
      ref={ref}
      className={cn(
        'transition-colors hover:bg-muted/50 data-[state=selected]:bg-muted',
        className,
      )}
      {...props}
    />
  ),
);
TableRow.displayName = 'TableRow';

const TableHead = React.forwardRef<
  HTMLTableCellElement,
  React.ThHTMLAttributes<HTMLTableCellElement>
>(({ className, ...props }, ref) => (
  <th
    ref={ref}
    className={cn(
      'h-12 whitespace-nowrap px-4 text-left align-middle text-xs font-semibold uppercase tracking-wide text-muted-foreground',
      className,
    )}
    {...props}
  />
));
TableHead.displayName = 'TableHead';

const TableCell = React.forwardRef<
  HTMLTableCellElement,
  React.TdHTMLAttributes<HTMLTableCellElement>
>(({ className, ...props }, ref) => (
  <td ref={ref} className={cn('px-4 py-3.5 align-middle', className)} {...props} />
));
TableCell.displayName = 'TableCell';

export { Table, TableBody, TableCell, TableHead, TableHeader, TableRow };
