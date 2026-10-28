import { Injectable } from '@nestjs/common';
import { isMenuPublishWarningDue, upcomingWeekStart, type BannerView } from '@meal/shared';
import { ClockService } from '../clock/clock.service';
import { notFound } from '../common/api-error';
import { PrismaService } from '../prisma/prisma.service';
import type { RequestPrincipal } from '../rbac/rbac.types';
import { SettingsService } from '../settings/settings.service';

const MAX_NOTIFICATION_BANNERS = 5;

@Injectable()
export class MeService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly settings: SettingsService,
    private readonly clock: ClockService,
  ) {}

  /**
   * Banners for the shared layout (plan section 4): BR-06 "menu not published" for HC admins,
   * computed on every request, and the caller's unread in-app notices (BR-14).
   */
  async banners(p: RequestPrincipal): Promise<BannerView[]> {
    const banners: BannerView[] = [];
    if (p.hcFactoryIds.includes(p.factoryId)) {
      banners.push(...(await this.menuWarnings(p.factoryId)));
    }
    const notices = await this.prisma.notification.findMany({
      where: { employeeId: p.employeeId, readAt: null },
      orderBy: { createdAt: 'desc' },
      take: MAX_NOTIFICATION_BANNERS,
    });
    for (const n of notices) {
      banners.push({
        id: `notification:${n.id}`,
        severity: 'info',
        kind: 'NOTIFICATION',
        notificationId: n.id,
        notificationType: n.type,
        params: n.payload as Record<string, string>,
      });
    }
    return banners;
  }

  /** BR-06: one warning per active canteen whose next-week menu is still unpublished. */
  async menuWarnings(factoryId: string): Promise<BannerView[]> {
    const now = this.clock.now();
    const weekStart = upcomingWeekStart(now);
    const warnRule = await this.settings.get(factoryId, 'menu.publishWarnAt');
    const canteens = await this.prisma.canteen.findMany({
      where: { factoryId, active: true },
      include: {
        menuWeeks: {
          where: { weekStart: new Date(`${weekStart}T00:00:00.000Z`) },
          select: { status: true },
        },
      },
      orderBy: { code: 'asc' },
    });
    return canteens
      .filter((c) =>
        isMenuPublishWarningDue({
          now,
          weekStart,
          warnRule,
          published: c.menuWeeks.some((w) => w.status !== 'DRAFT'),
        }),
      )
      .map((c) => ({
        id: `menu-not-published:${c.id}:${weekStart}`,
        severity: 'warning' as const,
        kind: 'MENU_NOT_PUBLISHED' as const,
        params: { canteen: c.name, weekStart },
      }));
  }

  async markRead(employeeId: string, notificationId: string): Promise<{ id: string }> {
    const n = await this.prisma.notification.findUnique({ where: { id: notificationId } });
    // Someone else's notice looks the same as a missing one.
    if (!n || n.employeeId !== employeeId) throw notFound('Notification not found');
    if (!n.readAt) {
      await this.prisma.notification.update({
        where: { id: notificationId },
        data: { readAt: this.clock.now() },
      });
    }
    return { id: notificationId };
  }
}
