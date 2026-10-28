import { randomInt } from 'node:crypto';
import { HttpStatus, Injectable, OnModuleInit } from '@nestjs/common';
import * as argon2 from 'argon2';
import { isPinLocked, registerPinFailure, type ResetPinResult } from '@meal/shared';
import { AuditService } from '../audit/audit.service';
import { ClockService } from '../clock/clock.service';
import { ApiException } from '../common/api-error';
import { PrismaService } from '../prisma/prisma.service';
import { SettingsService } from '../settings/settings.service';
import { SessionService } from './session.service';

interface PinSubject {
  id: string;
  pinHash: string | null;
  pinFailedCount: number;
  lockedUntil: Date | null;
  factoryId: string;
}

/** Employee code + 6-digit PIN sign-in (plan 4.1 /login, /change-pin; section 6.4 pin.* settings). */
@Injectable()
export class PinAuthService implements OnModuleInit {
  /** Verified against when the code is unknown, so timing does not reveal which codes exist. */
  private dummyHash = '';

  constructor(
    private readonly prisma: PrismaService,
    private readonly clock: ClockService,
    private readonly settings: SettingsService,
    private readonly sessions: SessionService,
    private readonly audit: AuditService,
  ) {}

  async onModuleInit(): Promise<void> {
    this.dummyHash = await argon2.hash('000000', { type: argon2.argon2id });
  }

  hashPin(pin: string): Promise<string> {
    return argon2.hash(pin, { type: argon2.argon2id });
  }

  /** Signs in; returns the session token and the employee id. */
  async login(
    employeeCode: string,
    pin: string,
    userAgent?: string,
  ): Promise<{ employeeId: string; sessionId: string; token: string; expiresAt: Date }> {
    const employee = await this.prisma.employee.findUnique({
      where: { employeeCode },
      include: { orgUnit: { select: { factoryId: true } } },
    });

    if (!employee || employee.status !== 'ACTIVE' || !employee.pinHash) {
      await argon2.verify(this.dummyHash, pin).catch(() => false);
      throw this.invalidCredentials();
    }

    await this.verifyOrRegisterFailure(
      {
        id: employee.id,
        pinHash: employee.pinHash,
        pinFailedCount: employee.pinFailedCount,
        lockedUntil: employee.lockedUntil,
        factoryId: employee.orgUnit.factoryId,
      },
      pin,
    );

    if (employee.pinFailedCount !== 0 || employee.lockedUntil) {
      await this.prisma.employee.update({
        where: { id: employee.id },
        data: { pinFailedCount: 0, lockedUntil: null },
      });
    }
    const session = await this.sessions.create(employee.id, 'PIN', userAgent);
    return {
      employeeId: employee.id,
      sessionId: session.id,
      token: session.token,
      expiresAt: session.expiresAt,
    };
  }

  /** Changes the caller's PIN, clears the forced-change flag and signs out their other sessions. */
  async changePin(
    employeeId: string,
    sessionId: string,
    currentPin: string,
    newPin: string,
  ): Promise<void> {
    const employee = await this.prisma.employee.findUniqueOrThrow({
      where: { id: employeeId },
      include: { orgUnit: { select: { factoryId: true } } },
    });
    if (!employee.pinHash) throw this.invalidCredentials();

    await this.verifyOrRegisterFailure(
      {
        id: employee.id,
        pinHash: employee.pinHash,
        pinFailedCount: employee.pinFailedCount,
        lockedUntil: employee.lockedUntil,
        factoryId: employee.orgUnit.factoryId,
      },
      currentPin,
    );

    if (currentPin === newPin) {
      throw new ApiException(
        HttpStatus.BAD_REQUEST,
        'PIN_UNCHANGED',
        'New PIN must differ from the current PIN',
      );
    }

    await this.prisma.employee.update({
      where: { id: employeeId },
      data: {
        pinHash: await this.hashPin(newPin),
        pinMustChange: false,
        pinFailedCount: 0,
        lockedUntil: null,
      },
    });
    await this.sessions.revokeAllForEmployee(employeeId, sessionId);
    await this.audit.log({
      actorId: employeeId,
      action: 'auth.pin.changed',
      entityType: 'Employee',
      entityId: employeeId,
    });
  }

  /**
   * Admin PIN reset: issues a temporary PIN, forces a change at next sign-in and ends the
   * employee's sessions. The temporary PIN is returned once and never stored or audited in clear.
   */
  async resetPin(actorId: string, employeeCode: string, reason?: string): Promise<ResetPinResult> {
    const employee = await this.prisma.employee.findUnique({ where: { employeeCode } });
    if (!employee) throw new ApiException(HttpStatus.NOT_FOUND, 'NOT_FOUND', 'Employee not found');

    const temporaryPin = String(randomInt(0, 1_000_000)).padStart(6, '0');
    await this.prisma.employee.update({
      where: { id: employee.id },
      data: {
        pinHash: await this.hashPin(temporaryPin),
        pinMustChange: true,
        pinFailedCount: 0,
        lockedUntil: null,
      },
    });
    await this.sessions.revokeAllForEmployee(employee.id);
    await this.audit.log({
      actorId,
      action: 'employee.pin.reset',
      entityType: 'Employee',
      entityId: employee.id,
      before: { pinMustChange: employee.pinMustChange },
      after: { pinMustChange: true },
      reason,
    });
    return { temporaryPin };
  }

  private async verifyOrRegisterFailure(subject: PinSubject, pin: string): Promise<void> {
    const now = this.clock.now();
    if (isPinLocked(subject.lockedUntil, now) && subject.lockedUntil) {
      throw this.locked(subject.lockedUntil);
    }
    if (subject.pinHash && (await argon2.verify(subject.pinHash, pin).catch(() => false))) return;

    const [maxAttempts, lockMinutes] = await Promise.all([
      this.settings.get(subject.factoryId, 'pin.maxAttempts'),
      this.settings.get(subject.factoryId, 'pin.lockMinutes'),
    ]);
    const next = registerPinFailure({
      failedCount: subject.pinFailedCount,
      maxAttempts,
      lockMinutes,
      now,
    });
    await this.prisma.employee.update({
      where: { id: subject.id },
      data: { pinFailedCount: next.failedCount, lockedUntil: next.lockedUntil },
    });
    if (next.lockedUntil) throw this.locked(next.lockedUntil);
    throw this.invalidCredentials();
  }

  private invalidCredentials(): ApiException {
    return new ApiException(
      HttpStatus.UNAUTHORIZED,
      'INVALID_CREDENTIALS',
      'Invalid employee code or PIN',
    );
  }

  private locked(until: Date): ApiException {
    return new ApiException(HttpStatus.LOCKED, 'PIN_LOCKED', 'Too many wrong attempts', {
      lockedUntil: until.toISOString(),
    });
  }
}
