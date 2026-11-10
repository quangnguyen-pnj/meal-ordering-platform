'use client';

import { useMutation } from '@tanstack/react-query';
import { Check, Download, FileUp } from 'lucide-react';
import { useState } from 'react';
import { AppDialog } from '@/components/app-dialog';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { api, ApiError } from '@/lib/api';
import { apiErrorText } from '@/lib/errors';
import { t, vi } from '@/messages';

export interface ImportOutcome {
  status: 'PREVIEW' | 'APPLIED' | 'REJECTED';
}

/** What the dialog shows for a result: a line of text, error lines, and whether to offer "apply". */
export interface ImportSummary {
  text: string;
  errors: string[];
  /** Label of the apply button; null when there is nothing to apply. */
  applyLabel: string | null;
}

/** Vietnamese text for a refused upload (wrong template, unreadable file, ...). */
export function importFileError(error: unknown): string {
  if (error instanceof ApiError && Array.isArray(error.details)) {
    const code = (error.details[0] as { message?: string } | undefined)?.message;
    const known = vi.org.fileErrors as Record<string, string>;
    if (code && known[code]) return known[code];
  }
  return apiErrorText(error);
}

/**
 * Excel import in two steps, like the mapping import: download the template, upload a file to
 * check it (nothing is written), then apply. The server validates again when applying.
 */
export function ExcelImportDialog<R extends ImportOutcome>({
  open,
  onClose,
  title,
  hint,
  template,
  upload,
  summarize,
  onApplied,
  labels,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  hint: string;
  template: { path: string; fileName: string };
  /** POSTs the file; `apply = false` is the dry run. */
  upload: (file: File, apply: boolean) => Promise<R>;
  summarize: (result: R) => ImportSummary;
  onApplied: (result: R) => void;
  labels: { template: string; file: string; check: string };
}) {
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<R | null>(null);
  const download = useMutation({
    mutationFn: () => api.download(template.path, template.fileName),
  });
  const run = useMutation({
    mutationFn: ({ f, apply }: { f: File; apply: boolean }) => upload(f, apply),
    onSuccess: (res) => {
      setResult(res);
      if (res.status === 'APPLIED') onApplied(res);
    },
  });
  const summary = result ? summarize(result) : null;

  function close() {
    setFile(null);
    setResult(null);
    run.reset();
    onClose();
  }

  return (
    <AppDialog
      open={open}
      onClose={close}
      title={title}
      description={hint}
      footer={
        <>
          <Button variant="outline" onClick={close}>
            {result?.status === 'APPLIED' ? t('common.close') : t('common.cancel')}
          </Button>
          {summary?.applyLabel && result?.status === 'PREVIEW' && file ? (
            <Button disabled={run.isPending} onClick={() => run.mutate({ f: file, apply: true })}>
              <Check className="h-4 w-4" aria-hidden />
              {run.isPending ? t('common.saving') : summary.applyLabel}
            </Button>
          ) : null}
        </>
      }
    >
      <div className="flex flex-col gap-5">
        <div>
          <Button variant="outline" disabled={download.isPending} onClick={() => download.mutate()}>
            <Download className="h-4 w-4" aria-hidden />
            {labels.template}
          </Button>
        </div>
        {download.error ? (
          <Alert variant="destructive">{apiErrorText(download.error)}</Alert>
        ) : null}
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            if (file && !run.isPending) run.mutate({ f: file, apply: false });
          }}
        >
          <div className="flex min-w-60 flex-1 flex-col gap-2">
            <Label htmlFor="import-file">{labels.file}</Label>
            <Input
              id="import-file"
              type="file"
              accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
              onChange={(e) => {
                setFile(e.target.files?.[0] ?? null);
                setResult(null);
                run.reset();
              }}
            />
          </div>
          <Button type="submit" variant="secondary" disabled={!file || run.isPending}>
            <FileUp className="h-4 w-4" aria-hidden />
            {labels.check}
          </Button>
        </form>
        {run.error ? <Alert variant="destructive">{importFileError(run.error)}</Alert> : null}
        {summary ? (
          <Alert role="status" variant={result?.status === 'REJECTED' ? 'destructive' : 'default'}>
            <p className="font-medium">{summary.text}</p>
            {summary.errors.length > 0 ? (
              <ul className="mt-2 max-h-60 list-disc overflow-y-auto pl-5 text-sm">
                {summary.errors.map((line, i) => (
                  <li key={i}>{line}</li>
                ))}
              </ul>
            ) : null}
          </Alert>
        ) : null}
      </div>
    </AppDialog>
  );
}
