import { Module } from '@nestjs/common';
import { MappingImportService } from './mapping-import.service';
import { OrgSyncService } from './org-sync.service';
import { OrgController } from './org.controller';
import { OrgService } from './org.service';

@Module({
  controllers: [OrgController],
  providers: [OrgService, MappingImportService, OrgSyncService],
  exports: [OrgSyncService],
})
export class OrgModule {}
