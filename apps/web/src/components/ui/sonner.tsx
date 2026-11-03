'use client';

import { Toaster as Sonner } from 'sonner';

type ToasterProps = React.ComponentProps<typeof Sonner>;

/** shadcn/ui Toaster (light theme only; the app has no dark mode). */
function Toaster(props: ToasterProps) {
  return (
    <Sonner
      theme="light"
      position="top-center"
      className="toaster group"
      toastOptions={{
        classNames: {
          toast:
            'group toast group-[.toaster]:bg-background group-[.toaster]:text-foreground group-[.toaster]:border-transparent group-[.toaster]:shadow-raised',
          description: 'group-[.toast]:text-muted-foreground',
          actionButton: '!bg-primary !text-primary-foreground',
          cancelButton: '!bg-muted !text-muted-foreground',
        },
      }}
      {...props}
    />
  );
}

export { Toaster };
