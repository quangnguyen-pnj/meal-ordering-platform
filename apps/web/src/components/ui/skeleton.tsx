import { Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { t } from '@/messages';

// shadcn/ui Skeleton, plus a spinner and a list placeholder built on it.
function Skeleton({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      aria-hidden
      className={cn('animate-pulse rounded-md bg-foreground/[0.06]', className)}
      {...props}
    />
  );
}

function Spinner({ className }: { className?: string }) {
  return <Loader2 aria-hidden className={cn('h-4 w-4 animate-spin', className)} />;
}

/** Placeholder rows while a list loads; announced once to screen readers. */
function LoadingRows({ rows = 5, className }: { rows?: number; className?: string }) {
  return (
    <div role="status" className={cn('flex flex-col gap-2', className)}>
      <span className="sr-only">{t('common.loading')}</span>
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-10 w-full" />
      ))}
    </div>
  );
}

/**
 * Wraps a result that stays on screen while the next one loads (React Query `placeholderData`),
 * so changing a filter never swaps the list for a skeleton and back. It dims only after a short
 * beat, so a quick answer shows no change at all. Not inert, so keyboard focus is never lost;
 * a screen disables the actions that must not run on the old result (save, import, print).
 */
function Refreshing({
  busy,
  className,
  children,
}: {
  busy: boolean;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <div
      aria-busy={busy || undefined}
      className={cn('transition-opacity duration-200', busy && 'opacity-50 delay-150', className)}
    >
      {children}
    </div>
  );
}

export { LoadingRows, Refreshing, Skeleton, Spinner };
