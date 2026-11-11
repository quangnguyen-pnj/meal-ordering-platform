'use client';

import * as TabsPrimitive from '@radix-ui/react-tabs';
import * as React from 'react';
import { cn } from '@/lib/utils';

const Tabs = TabsPrimitive.Root;

/**
 * "default": a tinted block with the picked tab raised in it, for the tabs of a page.
 * "line": no block, the picked tab is underlined; for tabs inside a tab (the days of the grid),
 * so the two levels do not look alike.
 */
type TabsVariant = 'default' | 'line';

const TabsList = React.forwardRef<
  React.ElementRef<typeof TabsPrimitive.List>,
  React.ComponentPropsWithoutRef<typeof TabsPrimitive.List> & { variant?: TabsVariant }
>(({ className, variant = 'default', ...props }, ref) => (
  <TabsPrimitive.List
    ref={ref}
    data-variant={variant}
    className={cn(
      // One row that scrolls sideways on a phone instead of wrapping into an uneven second row.
      'group/tabs inline-flex h-auto max-w-full items-center justify-start gap-1 overflow-x-auto text-muted-foreground [scrollbar-width:none] [&::-webkit-scrollbar]:hidden',
      variant === 'default' && 'rounded-lg bg-muted p-1',
      className,
    )}
    {...props}
  />
));
TabsList.displayName = TabsPrimitive.List.displayName;

const TabsTrigger = React.forwardRef<
  React.ElementRef<typeof TabsPrimitive.Trigger>,
  React.ComponentPropsWithoutRef<typeof TabsPrimitive.Trigger>
>(({ className, onFocus, ...props }, ref) => (
  <TabsPrimitive.Trigger
    ref={ref}
    // Arrow keys and clicks bring a tab that is half out of the scrolled row fully into view.
    onFocus={(e) => {
      e.currentTarget.scrollIntoView({ block: 'nearest', inline: 'nearest' });
      onFocus?.(e);
    }}
    // relative: hidden text for screen readers (sr-only is absolute) stays inside the scrolling row
    // instead of widening the whole page on a phone; it also places the "line" underline.
    className={cn(
      'relative inline-flex min-h-10 shrink-0 items-center justify-center whitespace-nowrap rounded-md px-3 py-1 text-sm font-medium transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50 data-[state=active]:text-foreground',
      'group-data-[variant=default]/tabs:data-[state=active]:bg-background group-data-[variant=default]/tabs:data-[state=active]:shadow',
      // line: the list has no padding, so the focus ring is drawn inside the tab, not cut off.
      'group-data-[variant=line]/tabs:px-2 group-data-[variant=line]/tabs:hover:text-foreground group-data-[variant=line]/tabs:focus-visible:ring-inset group-data-[variant=line]/tabs:focus-visible:ring-offset-0',
      'after:absolute after:inset-x-2 after:bottom-0 after:h-0.5 after:rounded-full after:bg-primary after:opacity-0 group-data-[variant=line]/tabs:data-[state=active]:after:opacity-100',
      className,
    )}
    {...props}
  />
));
TabsTrigger.displayName = TabsPrimitive.Trigger.displayName;

const TabsContent = React.forwardRef<
  React.ElementRef<typeof TabsPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof TabsPrimitive.Content>
>(({ className, ...props }, ref) => (
  <TabsPrimitive.Content
    ref={ref}
    className={cn(
      'mt-6 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
      className,
    )}
    {...props}
  />
));
TabsContent.displayName = TabsPrimitive.Content.displayName;

export { Tabs, TabsContent, TabsList, TabsTrigger };
