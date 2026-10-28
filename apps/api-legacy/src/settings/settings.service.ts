import { Injectable, Logger } from '@nestjs/common';
import type { Prisma } from '@prisma/client';
import {
  SETTING_KEYS,
  defaultSettingValue,
  isSettingKey,
  parseSettingValue,
  type SettingKey,
  type SettingValue,
  type SettingView,
} from '@meal/shared';
import { AuditService } from '../audit/audit.service';
import { invalid, notFound } from '../common/api-error';
import { PrismaService } from '../prisma/prisma.service';

/** Per-factory settings with the plan 6.4 defaults. Deadlines are never hardcoded (rule 3). */
@Injectable()
export class SettingsService {
  private readonly logger = new Logger(SettingsService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
  ) {}

  async get<K extends SettingKey>(factoryId: string, key: K): Promise<SettingValue<K>> {
    const row = await this.prisma.setting.findUnique({
      where: { factoryId_key: { factoryId, key } },
    });
    if (!row) return defaultSettingValue(key);
    try {
      return parseSettingValue(key, row.value);
    } catch {
      this.logger.warn(`Setting "${key}" of factory ${factoryId} is malformed; using the default`);
      return defaultSettingValue(key);
    }
  }

  /** Every plan 6.4 key with its effective value and default, for /admin/settings. */
  async list(factoryId: string): Promise<SettingView[]> {
    const rows = await this.prisma.setting.findMany({ where: { factoryId } });
    const byKey = new Map(rows.map((r) => [r.key, r]));
    return Promise.all(
      SETTING_KEYS.map(async (key) => ({
        key,
        value: await this.get(factoryId, key),
        defaultValue: defaultSettingValue(key),
        updatedAt: byKey.get(key)?.updatedAt.toISOString() ?? null,
      })),
    );
  }

  /** Validates the value against the key's schema, stores it and audits before / after. */
  async set(actorId: string, factoryId: string, key: string, raw: unknown): Promise<SettingView> {
    if (!isSettingKey(key)) throw notFound('Unknown setting');
    let value: unknown;
    try {
      value = parseSettingValue(key, raw);
    } catch {
      throw invalid('Invalid value for this setting', [{ path: 'value', message: key }]);
    }
    const before = await this.get(factoryId, key);
    await this.prisma.$transaction(async (tx) => {
      await tx.setting.upsert({
        where: { factoryId_key: { factoryId, key } },
        update: { value: value as Prisma.InputJsonValue },
        create: { factoryId, key, value: value as Prisma.InputJsonValue },
      });
      await this.audit.log(
        {
          actorId,
          action: 'setting.updated',
          entityType: 'Setting',
          entityId: `${factoryId}:${key}`,
          before: before as Prisma.InputJsonValue,
          after: value as Prisma.InputJsonValue,
        },
        tx,
      );
    });
    return (await this.list(factoryId)).find((s) => s.key === key)!;
  }
}
