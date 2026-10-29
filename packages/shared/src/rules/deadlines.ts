const MS_PER_MINUTE = 60_000;

/**
 * BR-03: what the UI shows for a closeAt. closeAt is exclusive (00:00 Friday), so screens show
 * the last accepted minute (23:59 Thursday).
 */
export function lastAcceptedMinute(closeAt: Date): Date {
  return new Date(closeAt.getTime() - MS_PER_MINUTE);
}
