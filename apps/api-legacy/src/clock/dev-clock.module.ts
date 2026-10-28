import { Module } from '@nestjs/common';
import { DevClockController } from './dev-clock.controller';

@Module({ controllers: [DevClockController] })
export class DevClockModule {}
