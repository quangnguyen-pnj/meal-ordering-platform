import { Module } from '@nestjs/common';
import { AdminRegistrationsService } from './admin-registrations.service';
import { ForecastService } from './forecast.service';
import { MyRegistrationsService } from './my-registrations.service';
import { RegistrationImportService } from './registration-import.service';
import { RegistrationStore } from './registration-store';
import {
  AdminRegistrationsController,
  MyRegistrationsController,
  TeamController,
} from './registrations.controller';

@Module({
  controllers: [MyRegistrationsController, TeamController, AdminRegistrationsController],
  providers: [
    RegistrationStore,
    ForecastService,
    MyRegistrationsService,
    AdminRegistrationsService,
    RegistrationImportService,
  ],
  exports: [ForecastService],
})
export class RegistrationsModule {}
