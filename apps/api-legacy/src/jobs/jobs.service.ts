import {
  Inject,
  Injectable,
  Logger,
  type OnApplicationBootstrap,
  type OnApplicationShutdown,
} from '@nestjs/common';
import PgBoss from 'pg-boss';
import { ENV, type AppEnv } from '../config/env';
import { MeService } from '../me/me.service';
import { OrgSyncService } from '../org/org-sync.service';
import { PrismaService } from '../prisma/prisma.service';
import { ForecastService } from '../registrations/forecast.service';

const TZ = 'Asia/Ho_Chi_Minh';

/**
 * Background jobs on pg-boss (plan 6.5). Jobs only do proactive work; every deadline is still
 * checked on each request, so a missed run never breaks a rule. Handlers are plain methods so
 * tests call them directly; pg-boss itself starts only when JOBS_ENABLED (off in tests).
 */
export const JOBS = {
  /** Daily at 02:00: org tree and employees from DMP (BR-60..BR-63). */
  syncOrgEmployees: { name: 'sync-org-employees', cron: '0 2 * * *' },
  /**
   * BR-06: from `menu.publishWarnAt` (Monday 12:00), hourly until published. It runs every hour
   * and checks the setting, so moving the warning time needs no new schedule.
   */
  menuPublishWarning: { name: 'menu-publish-warning', cron: '0 * * * *' },
  /**
   * BR-07: the forecast snapshot at each week's closeAt. closeAt differs per week (BR-04), so
   * the job runs every minute and snapshots whatever is due; requests also catch up (plan 6.5).
   */
  closeWeek: { name: 'close-week', cron: '* * * * *' },
  /** BR-14: registrations still waiting for reselection are cancelled at the day's lock. */
  reselectDeadline: { name: 'reselect-deadline', cron: '* * * * *' },
} as const;

@Injectable()
export class JobsService implements OnApplicationBootstrap, OnApplicationShutdown {
  private readonly logger = new Logger(JobsService.name);
  private boss: PgBoss | null = null;

  constructor(
    @Inject(ENV) private readonly env: AppEnv,
    private readonly prisma: PrismaService,
    private readonly orgSync: OrgSyncService,
    private readonly me: MeService,
    private readonly forecast: ForecastService,
  ) {}

  async onApplicationBootstrap(): Promise<void> {
    if (!this.env.JOBS_ENABLED) {
      this.logger.log('Background jobs are disabled (JOBS_ENABLED=false)');
      return;
    }
    const url = new URL(this.env.DATABASE_URL);
    url.searchParams.delete('schema'); // Prisma-only parameter
    const boss = new PgBoss({ connectionString: url.toString(), schema: 'pgboss' });
    boss.on('error', (error) => this.logger.error(`pg-boss: ${error.message}`));
    await boss.start();
    for (const job of Object.values(JOBS)) {
      await boss.createQueue(job.name);
      await boss.schedule(job.name, job.cron, {}, { tz: TZ });
    }
    await boss.work(JOBS.syncOrgEmployees.name, async () => this.syncOrgEmployees());
    await boss.work(JOBS.menuPublishWarning.name, async () => this.menuPublishWarning());
    await boss.work(JOBS.closeWeek.name, async () => this.closeWeek());
    await boss.work(JOBS.reselectDeadline.name, async () => this.reselectDeadline());
    this.boss = boss;
    this.logger.log(`Scheduled ${Object.keys(JOBS).length} jobs`);
  }

  async onApplicationShutdown(): Promise<void> {
    await this.boss?.stop({ graceful: true, timeout: 10_000 });
    this.boss = null;
  }

  /** Idempotent: a second run on the same day simply finds nothing to change. */
  async syncOrgEmployees(): Promise<void> {
    for (const factory of await this.prisma.factory.findMany({ select: { id: true } })) {
      const result = await this.orgSync.run(factory.id, null);
      this.logger.log(`sync-org-employees ${factory.id}: ${result.status}`);
    }
  }

  /** BR-06: the banner is computed per request; the job leaves a trace in the logs. */
  async menuPublishWarning(): Promise<number> {
    let count = 0;
    for (const factory of await this.prisma.factory.findMany({ select: { id: true } })) {
      for (const warning of await this.me.menuWarnings(factory.id)) {
        count += 1;
        this.logger.warn(
          `Menu for ${warning.params.weekStart} of ${warning.params.canteen} is not published yet`,
        );
      }
    }
    return count;
  }

  /** BR-07: idempotent; a week already snapshotted is skipped. */
  async closeWeek(): Promise<number> {
    const closed = await this.forecast.closeDueWeeks();
    if (closed > 0) this.logger.log(`close-week: ${closed} forecast snapshot(s) taken`);
    return closed;
  }

  /** BR-14: idempotent; only registrations still waiting for reselection change. */
  async reselectDeadline(): Promise<number> {
    const cancelled = await this.forecast.cancelUnselectedAtLock();
    if (cancelled > 0) this.logger.log(`reselect-deadline: ${cancelled} registration(s) cancelled`);
    return cancelled;
  }
}
