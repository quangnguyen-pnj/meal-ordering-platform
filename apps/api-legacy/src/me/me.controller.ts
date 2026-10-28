import { Controller, Get, HttpCode, HttpStatus, Param, Post } from '@nestjs/common';
import { Authenticated, CurrentPrincipal } from '../rbac/decorators';
import type { RequestPrincipal } from '../rbac/rbac.types';
import { MeService } from './me.service';

/** The signed-in user's own banners and notices; data is always filtered to the caller. */
@Controller('me')
export class MeController {
  constructor(private readonly me: MeService) {}

  @Authenticated()
  @Get('banners')
  async banners(@CurrentPrincipal() p: RequestPrincipal) {
    return { items: await this.me.banners(p) };
  }

  @Authenticated()
  @Post('notifications/:id/read')
  @HttpCode(HttpStatus.OK)
  markRead(@Param('id') id: string, @CurrentPrincipal() p: RequestPrincipal) {
    return this.me.markRead(p.employeeId, id);
  }
}
