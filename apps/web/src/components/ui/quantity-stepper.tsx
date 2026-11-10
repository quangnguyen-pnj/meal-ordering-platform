'use client';

import { Minus, Plus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { fieldSurface } from '@/components/ui/field';
import { cn } from '@/lib/utils';

/**
 * BR-20: a quantity from 0 with no upper limit. 44 px targets for tablets; the field also takes
 * typed numbers. Labels come from the caller (messages file).
 */
export function QuantityStepper({
  value,
  onChange,
  label,
  increaseLabel,
  decreaseLabel,
  disabled,
  className,
}: {
  value: number;
  onChange: (value: number) => void;
  label: string;
  increaseLabel: string;
  decreaseLabel: string;
  disabled?: boolean;
  className?: string;
}) {
  return (
    <div className={cn('flex items-center gap-1', className)}>
      <Button
        type="button"
        variant="ghost"
        size="icon"
        aria-label={decreaseLabel}
        disabled={disabled || value <= 0}
        onClick={() => onChange(Math.max(0, value - 1))}
      >
        <Minus className="h-4 w-4" aria-hidden />
      </Button>
      <input
        type="number"
        inputMode="numeric"
        min={0}
        step={1}
        aria-label={label}
        disabled={disabled}
        value={value}
        onChange={(e) => {
          const n = Number(e.target.value);
          onChange(Number.isFinite(n) ? Math.max(0, Math.floor(n)) : 0);
        }}
        className={cn(
          fieldSurface,
          'h-10 w-12 text-center text-base tabular-nums sm:w-14 md:text-sm',
          '[appearance:textfield] [&::-webkit-inner-spin-button]:m-0 [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:m-0 [&::-webkit-outer-spin-button]:appearance-none',
          value > 0 &&
            'bg-primary/10 font-semibold text-primary ring-primary/30 hover:bg-primary/15',
        )}
      />
      <Button
        type="button"
        variant="ghost"
        size="icon"
        aria-label={increaseLabel}
        disabled={disabled}
        onClick={() => onChange(value + 1)}
      >
        <Plus className="h-4 w-4" aria-hidden />
      </Button>
    </div>
  );
}
