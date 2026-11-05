import * as React from 'react';
import { fieldSurface } from '@/components/ui/field';
import { cn } from '@/lib/utils';

// shadcn/ui Input, 44 px high for touch.
const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  ({ className, type, ...props }, ref) => (
    <input
      type={type}
      ref={ref}
      className={cn(
        fieldSurface,
        'flex h-11 w-full px-3 py-2 text-base md:text-sm file:border-0 file:bg-transparent file:text-sm file:font-medium file:text-foreground placeholder:text-muted-foreground',
        className,
      )}
      {...props}
    />
  ),
);
Input.displayName = 'Input';

export { Input };
