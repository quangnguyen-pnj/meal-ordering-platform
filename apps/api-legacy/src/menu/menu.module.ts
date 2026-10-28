import { Module } from '@nestjs/common';
import { MenuAdminController, MyMenuController } from './menu.controller';
import { MenuService } from './menu.service';

@Module({
  controllers: [MenuAdminController, MyMenuController],
  providers: [MenuService],
  exports: [MenuService],
})
export class MenuModule {}
