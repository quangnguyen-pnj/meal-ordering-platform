'use client';

import { ArrowLeft, KeyRound } from 'lucide-react';
import { zodResolver } from '@hookform/resolvers/zod';
import { ChangePinSchema, PinSchema } from '@meal/shared';
import { useMutation } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { useForm, type UseFormRegisterReturn } from 'react-hook-form';
import { z } from 'zod';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { PinInput } from '@/components/ui/pin-input';
import { ApiError, api } from '@/lib/api';
import { errorMessage, t } from '@/messages';

// The shared schema covers what the API checks; the confirmation field is a UI-only extra.
const FormSchema = z
  .object({ currentPin: PinSchema, newPin: PinSchema, confirmPin: z.string() })
  .superRefine((v, ctx) => {
    if (!ChangePinSchema.safeParse({ currentPin: v.currentPin, newPin: v.newPin }).success) {
      ctx.addIssue({ code: 'custom', path: ['newPin'], message: 'PIN_UNCHANGED' });
    }
    if (v.confirmPin !== v.newPin) {
      ctx.addIssue({ code: 'custom', path: ['confirmPin'], message: 'MISMATCH' });
    }
  });
type FormValues = z.infer<typeof FormSchema>;

function Field({
  id,
  label,
  error,
  register,
}: {
  id: keyof FormValues;
  label: string;
  error?: string;
  register: UseFormRegisterReturn;
}) {
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{label}</Label>
      <PinInput
        id={id}
        autoComplete={id === 'currentPin' ? 'current-password' : 'new-password'}
        aria-invalid={error ? true : undefined}
        {...register}
      />
      {error ? (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

export function ChangePinForm({ forced }: { forced: boolean }) {
  const router = useRouter();
  const form = useForm<FormValues>({
    resolver: zodResolver(FormSchema),
    defaultValues: { currentPin: '', newPin: '', confirmPin: '' },
  });
  const { errors } = form.formState;

  const change = useMutation({
    mutationFn: (v: FormValues) =>
      api.post('/auth/pin/change', { currentPin: v.currentPin, newPin: v.newPin }),
    onSuccess: () => {
      router.replace('/today');
      router.refresh();
    },
  });

  const fieldMessage = (name: keyof FormValues): string | undefined => {
    const message = errors[name]?.message;
    if (!message) return undefined;
    if (message === 'MISMATCH') return t('changePin.mismatch');
    if (message === 'PIN_UNCHANGED') return errorMessage('PIN_UNCHANGED');
    return t('login.pinFormat');
  };

  const serverError = change.error instanceof ApiError ? change.error : null;

  return (
    <form
      // POST, never the default GET: a submit before hydration must not put the PIN in the URL.
      method="post"
      noValidate
      onSubmit={form.handleSubmit((v) => change.mutate(v))}
      className="flex flex-col gap-5"
    >
      <p className="text-sm text-muted-foreground">
        {forced ? t('changePin.forcedIntro') : t('changePin.intro')}
      </p>
      <Field
        id="currentPin"
        label={t('changePin.currentPin')}
        error={fieldMessage('currentPin')}
        register={form.register('currentPin')}
      />
      <Field
        id="newPin"
        label={t('changePin.newPin')}
        error={fieldMessage('newPin')}
        register={form.register('newPin')}
      />
      <Field
        id="confirmPin"
        label={t('changePin.confirmPin')}
        error={fieldMessage('confirmPin')}
        register={form.register('confirmPin')}
      />

      {serverError ? (
        <Alert variant="destructive" role="alert">
          {serverError.status === 0 ? t('network') : errorMessage(serverError.code)}
        </Alert>
      ) : null}

      <div className="flex gap-3">
        <Button type="submit" disabled={change.isPending}>
          <KeyRound className="h-4 w-4" aria-hidden />
          {change.isPending ? t('changePin.submitting') : t('changePin.submit')}
        </Button>
        {forced ? null : (
          <Button type="button" variant="tertiary" onClick={() => router.back()}>
            <ArrowLeft className="h-4 w-4" aria-hidden />
            {t('changePin.cancel')}
          </Button>
        )}
      </div>
    </form>
  );
}
