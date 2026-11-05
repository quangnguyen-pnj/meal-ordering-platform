import { ChevronDown } from 'lucide-react';
import * as React from 'react';
import { fieldSurface } from '@/components/ui/field';
import { cn } from '@/lib/utils';

/** shadcn/ui NativeSelect: a real <select> styled like SelectTrigger (opens the OS picker). */
const NativeSelect = React.forwardRef<
  HTMLSelectElement,
  React.SelectHTMLAttributes<HTMLSelectElement>
>(({ className, ...props }, ref) => (
  <div className="relative w-full">
    <select
      ref={ref}
      className={cn(
        fieldSurface,
        'flex h-11 w-full appearance-none py-2 pl-3 pr-9 text-base',
        className,
      )}
      {...props}
    />
    <ChevronDown
      aria-hidden
      className="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 opacity-50"
    />
  </div>
));
NativeSelect.displayName = 'NativeSelect';

export { NativeSelect };
