import { cva, type VariantProps } from 'class-variance-authority';
import * as React from 'react';
import { cn } from '@/lib/utils';

// shadcn/ui Badge plus "success" and "warning" for statuses (published, draft, active).
const badgeVariants = cva(
  'inline-flex items-center gap-1 whitespace-nowrap rounded-md px-2 py-1 text-xs font-semibold transition-colors focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 [&_svg]:h-3.5 [&_svg]:w-3.5 [&_svg]:shrink-0',
  {
    // A tinted fill and strong text tell the statuses apart; no borders.
    variants: {
      variant: {
        default: 'bg-primary text-primary-foreground',
        secondary: 'bg-secondary text-secondary-foreground',
        destructive: 'bg-destructive/10 text-destructive',
        outline: 'bg-muted text-muted-foreground',
        success: 'bg-success/10 text-success',
        warning: 'bg-warning/20 text-foreground',
      },
    },
    defaultVariants: { variant: 'secondary' },
  },
);

export interface BadgeProps
  extends React.HTMLAttributes<HTMLSpanElement>, VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, ...props }: BadgeProps) {
  return <span className={cn(badgeVariants({ variant }), className)} {...props} />;
}

export { Badge, badgeVariants };
