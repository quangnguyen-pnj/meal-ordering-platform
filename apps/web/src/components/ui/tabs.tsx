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

interface IndicatorBox {
  left: number;
  top: number;
  width: number;
  height: number;
}

/**
 * The raised block ("default") or the underline ("line") is one element that slides to the picked
 * tab, instead of each tab drawing its own. It is placed from the tab's measured box, so it
 * follows resizes, a changing label and the row's own scrolling (it lives inside the scroller).
 * Until it is measured (server render, a hidden tab list), the picked tab draws its own mark.
 */
const TabsList = React.forwardRef<
  React.ElementRef<typeof TabsPrimitive.List>,
  React.ComponentPropsWithoutRef<typeof TabsPrimitive.List> & { variant?: TabsVariant }
>(({ className, variant = 'default', children, ...props }, ref) => {
  const listRef = React.useRef<HTMLDivElement | null>(null);
  const [box, setBox] = React.useState<IndicatorBox | null>(null);
  // Off for the first placement, so the mark does not slide in from the corner on load.
  const [animate, setAnimate] = React.useState(false);

  const setRefs = React.useCallback(
    (node: HTMLDivElement | null) => {
      listRef.current = node;
      if (typeof ref === 'function') ref(node);
      else if (ref) ref.current = node;
    },
    [ref],
  );

  React.useLayoutEffect(() => {
    const list = listRef.current;
    if (!list) return;
    const resizeObserver =
      typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(() => measure());

    function measure() {
      const active = list!.querySelector<HTMLElement>('[role="tab"][data-state="active"]');
      list!.querySelectorAll('[role="tab"]').forEach((tab) => resizeObserver?.observe(tab));
      const next =
        active && active.offsetWidth > 0
          ? {
              left: active.offsetLeft,
              top: active.offsetTop,
              width: active.offsetWidth,
              height: active.offsetHeight,
            }
          : null;
      setBox((prev) =>
        prev === next ||
        (prev &&
          next &&
          prev.left === next.left &&
          prev.top === next.top &&
          prev.width === next.width &&
          prev.height === next.height)
          ? prev
          : next,
      );
    }

    measure();
    const mutationObserver = new MutationObserver(measure);
    mutationObserver.observe(list, {
      attributes: true,
      attributeFilter: ['data-state'],
      childList: true,
      subtree: true,
    });
    resizeObserver?.observe(list);
    return () => {
      mutationObserver.disconnect();
      resizeObserver?.disconnect();
    };
  }, []);

  const placed = box !== null;
  React.useEffect(() => {
    if (!placed) return;
    const frame = requestAnimationFrame(() => setAnimate(true));
    return () => cancelAnimationFrame(frame);
  }, [placed]);

  return (
    <TabsPrimitive.List
      ref={setRefs}
      data-variant={variant}
      data-slide={placed ? 'on' : 'off'}
      className={cn(
        // One row that scrolls sideways on a phone instead of wrapping into an uneven second row.
        // relative: the sliding mark is positioned against this row.
        'group/tabs relative inline-flex h-auto max-w-full items-center justify-start gap-1 overflow-x-auto text-muted-foreground [scrollbar-width:none] [&::-webkit-scrollbar]:hidden',
        variant === 'default' && 'rounded-lg bg-muted p-1',
        className,
      )}
      {...props}
    >
      {box ? (
        <span
          aria-hidden
          className={cn(
            'pointer-events-none absolute',
            animate &&
              'transition-[left,top,width,height] duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] motion-reduce:transition-none',
            variant === 'default' ? 'rounded-md bg-background shadow' : 'rounded-full bg-primary',
          )}
          style={
            variant === 'default'
              ? { left: box.left, top: box.top, width: box.width, height: box.height }
              : // The underline sits 8px in from the tab's sides, on its bottom edge.
                {
                  left: box.left + 8,
                  top: box.top + box.height - 2,
                  width: box.width - 16,
                  height: 2,
                }
          }
        />
      ) : null}
      {children}
    </TabsPrimitive.List>
  );
});
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
    // instead of widening the whole page on a phone, and it stays above the list's sliding mark.
    className={cn(
      'relative inline-flex min-h-10 shrink-0 items-center justify-center whitespace-nowrap rounded-md px-3 py-1 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50 data-[state=active]:text-foreground',
      // Own mark of the picked tab, only while the list's sliding mark is not placed yet.
      'group-data-[variant=default]/tabs:group-data-[slide=off]/tabs:data-[state=active]:bg-background group-data-[variant=default]/tabs:group-data-[slide=off]/tabs:data-[state=active]:shadow',
      'after:absolute after:inset-x-2 after:bottom-0 after:h-0.5 after:rounded-full after:bg-primary after:opacity-0 group-data-[variant=line]/tabs:group-data-[slide=off]/tabs:data-[state=active]:after:opacity-100',
      // line: the list has no padding, so the focus ring is drawn inside the tab, not cut off.
      'group-data-[variant=line]/tabs:px-2 group-data-[variant=line]/tabs:hover:text-foreground group-data-[variant=line]/tabs:focus-visible:ring-inset group-data-[variant=line]/tabs:focus-visible:ring-offset-0',
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
