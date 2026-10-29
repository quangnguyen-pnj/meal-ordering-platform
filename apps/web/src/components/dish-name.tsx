import type { DishTypeLabel } from '@/lib/api-types';
import { cn } from '@/lib/utils';

/**
 * BR-12: a meal is "[Loại] Tên món". Since dish types are master data (2026-10-23) the type is
 * drawn in its own colour at weight 600, so the kinds of dish are told apart at a glance.
 */
export function DishTypeTag({ type, className }: { type: DishTypeLabel; className?: string }) {
  return (
    <span className={cn('font-semibold', className)} style={{ color: type.color }}>
      [{type.name}]
    </span>
  );
}

/** The dish name after the "[Loại] " prefix of a BR-12 display name. */
export function dishNameOf(displayName: string, type: DishTypeLabel): string {
  const prefix = `[${type.name}] `;
  return displayName.startsWith(prefix) ? displayName.slice(prefix.length) : displayName;
}

/** `[Loại]` in colour, then the dish name; screen readers read it like the plain text. */
export function DishName({
  type,
  name,
  displayName,
  className,
}: {
  type: DishTypeLabel;
  /** The dish name alone, or pass `displayName` ("[Loại] Tên món"). */
  name?: string;
  displayName?: string;
  className?: string;
}) {
  const dish = name ?? dishNameOf(displayName ?? '', type);
  return (
    <span className={className}>
      <DishTypeTag type={type} /> {dish}
    </span>
  );
}
