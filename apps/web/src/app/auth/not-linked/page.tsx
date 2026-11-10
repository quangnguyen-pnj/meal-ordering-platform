import { ArrowLeft } from 'lucide-react';
import Link from 'next/link';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { t } from '@/messages';

/** Shown when the Microsoft email matches no employee (plan section 5, SSO mapping). */
export default function NotLinkedPage() {
  return (
    <main className="flex min-h-screen items-center justify-center px-4 py-10">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>{t('notLinked.title')}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-5">
          <p className="text-sm">{t('notLinked.body')}</p>
          <Button asChild>
            <Link href="/login">
              <ArrowLeft className="h-4 w-4" aria-hidden />
              {t('notLinked.backToLogin')}
            </Link>
          </Button>
        </CardContent>
      </Card>
    </main>
  );
}
