import { Module } from '@nestjs/common';
import { MasterController, SupplierController } from './master.controller';
import { MasterService } from './master.service';

@Module({ controllers: [MasterController, SupplierController], providers: [MasterService] })
export class MasterModule {}
