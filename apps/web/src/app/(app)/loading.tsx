import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { t } from '@/messages';

/** Shown inside the shell while the next screen renders, so a click never looks ignored. */
export default function Loading() {
  return (
    <Card variant="page" role="status" aria-live="polite">
      <span className="sr-only">{t('common.loading')}</span>
      <CardHeader>
        <Skeleton className="h-7 w-56" />
        <Skeleton className="h-4 w-full max-w-md" />
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <Skeleton className="h-11 w-full max-w-sm" />
        {Array.from({ length: 5 }, (_, i) => (
          <Skeleton key={i} className="h-10 w-full" />
        ))}
      </CardContent>
    </Card>
  );
}
