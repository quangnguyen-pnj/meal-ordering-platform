import { CalendarDays, Construction } from 'lucide-react';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { findNavItem, isNavItemVisible } from '@/lib/nav';
import { getSession } from '@/lib/server-api';
import { t } from '@/messages';

/**
 * Stand-in for every screen that later phases deliver. It only exists for routes in the
 * navigation registry; anything else is a 404.
 */
export default async function PlaceholderPage({ params }: { params: Promise<{ slug: string[] }> }) {
  const { slug } = await params;
  const item = findNavItem(`/${slug.join('/')}`);
  if (!item) notFound();

  const user = await getSession();
  if (!user || !isNavItemVisible(item, user.roles)) {
    return <Alert variant="destructive">{t('placeholder.forbidden')}</Alert>;
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t(item.labelKey)}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col items-start gap-3">
        <div className="flex items-center gap-3">
          <Construction className="h-8 w-8 shrink-0 text-muted-foreground" aria-hidden />
          <div>
            <p className="font-medium">{t('placeholder.title')}</p>
            <p className="text-sm text-muted-foreground">{t('placeholder.body')}</p>
          </div>
        </div>
        {item.href !== '/week' ? (
          <Button asChild variant="outline">
            <Link href="/week">
              <CalendarDays className="h-4 w-4" aria-hidden />
              {t('placeholder.goToWeek')}
            </Link>
          </Button>
        ) : null}
      </CardContent>
    </Card>
  );
}
