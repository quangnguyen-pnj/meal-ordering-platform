import { addDays, isoWeekOf, lastAcceptedMinute, toVnDateTime, vnToUtc } from '@meal/shared';
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';
import { t } from '@/messages';

export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}

/** Only same-site paths are valid post-login destinations (blocks open redirects). */
export function safeNextPath(next: string | null | undefined, fallback = '/today'): string {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) {
    return fallback;
  }
  return next;
}

/** HH:mm in Asia/Ho_Chi_Minh for an ISO instant. */
export function formatVnTime(iso: string): string {
  return new Intl.DateTimeFormat('vi-VN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'Asia/Ho_Chi_Minh',
  }).format(new Date(iso));
}

/** The Asia/Ho_Chi_Minh calendar day (YYYY-MM-DD) of an ISO instant, e.g. the server's `now`. */
export function vnDateOf(iso: string): string {
  return toVnDateTime(new Date(iso)).date;
}

/** dd/MM/yyyy for a YYYY-MM-DD calendar date (serving days are dates, not instants). */
export function formatDate(date: string): string {
  const [y, m, d] = date.split('-');
  return `${d}/${m}/${y}`;
}

/** dd/MM for a YYYY-MM-DD calendar date, where the year is already clear (a week's day cards). */
export function formatDayMonth(date: string): string {
  const [, m, d] = date.split('-');
  return `${d}/${m}`;
}

/** "26/10 – 01/11/2026" for the Monday YYYY-MM-DD of a week (Monday to Sunday). */
export function formatWeekRange(weekStart: string): string {
  const end = addDays(weekStart, 6);
  const [, sm, sd] = weekStart.split('-');
  const [ey, em, ed] = end.split('-');
  return `${sd}/${sm} – ${ed}/${em}/${ey}`;
}

/** "Tuần 43" (ISO 8601 week) for the Monday YYYY-MM-DD of a week. */
export function formatWeekNumber(weekStart: string): string {
  return t('common.weekNumber', { week: isoWeekOf(weekStart).week });
}

/** "1–50 / 1.700" for a page of a list. */
export function formatRange(offset: number, count: number, total: number): string {
  const n = new Intl.NumberFormat('vi-VN');
  if (total === 0) return '0';
  return `${n.format(offset + 1)}–${n.format(offset + count)} / ${n.format(total)}`;
}

/** dd/MM/yyyy HH:mm in Asia/Ho_Chi_Minh for an ISO instant. */
export function formatVnDateTime(iso: string): string {
  const parts = new Intl.DateTimeFormat('vi-VN', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'Asia/Ho_Chi_Minh',
  }).formatToParts(new Date(iso));
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? '';
  return `${get('day')}/${get('month')}/${get('year')} ${get('hour')}:${get('minute')}`;
}

/** BR-03: a closeAt is shown as its last accepted minute, e.g. "22/10/2026 23:59". */
export function formatCloseAt(closeAtIso: string): string {
  return formatVnDateTime(lastAcceptedMinute(new Date(closeAtIso)).toISOString());
}

/**
 * BR-04: a closeAt as the value of a datetime-local input showing its last accepted minute in
 * Vietnam time ("2026-10-31T16:59" for a closeAt of 17:00), the same minute formatCloseAt shows.
 */
export function closeAtToVnInput(closeAtIso: string): string {
  const v = toVnDateTime(lastAcceptedMinute(new Date(closeAtIso)));
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${v.date}T${pad(v.hour)}:${pad(v.minute)}`;
}

/** The inverse of closeAtToVnInput: the exclusive closeAt one minute after the chosen minute. */
export function vnInputToCloseAt(value: string): string | null {
  const [date, time] = value.split('T');
  if (!date || !time) return null;
  try {
    return new Date(vnToUtc(date, time.slice(0, 5)).getTime() + 60_000).toISOString();
  } catch {
    return null;
  }
}
