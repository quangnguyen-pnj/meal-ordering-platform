package vn.pnj.meal.api.me;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.pnj.meal.api.access.Authenticated;
import vn.pnj.meal.api.access.RequestPrincipal;
import vn.pnj.meal.api.me.MeService.BannerView;

/** The signed-in user's own banners and notices; data is always filtered to the caller. */
@RestController
@RequestMapping("/me")
public class MeController {
  private final MeService me;

  public MeController(MeService me) {
    this.me = me;
  }

  public record Items<T>(List<T> items) {}

  @Authenticated
  @GetMapping("/banners")
  Items<BannerView> banners(RequestPrincipal p) {
    return new Items<>(me.banners(p));
  }

  @Authenticated
  @PostMapping("/notifications/{id}/read")
  MeService.Read markRead(@PathVariable String id, RequestPrincipal p) {
    return me.markRead(p.employeeId(), id);
  }
}
