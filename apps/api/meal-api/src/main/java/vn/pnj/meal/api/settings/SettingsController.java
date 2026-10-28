package vn.pnj.meal.api.settings;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.pnj.meal.api.access.RequestPrincipal;
import vn.pnj.meal.api.access.RequirePermission;
import vn.pnj.meal.api.access.ScopeType;
import vn.pnj.meal.api.common.JsonBody;
import vn.pnj.meal.api.settings.SettingsService.SettingView;
import vn.pnj.meal.rules.access.Action;

/** /admin/settings: the plan 6.4 keys (HC admin and system admin). */
@RestController
@RequestMapping("/admin/settings")
public class SettingsController {
  private final SettingsService settings;

  public SettingsController(SettingsService settings) {
    this.settings = settings;
  }

  public record Items<T>(List<T> items) {}

  @RequirePermission(action = Action.SETTINGS_MANAGE, scope = ScopeType.FACTORY)
  @GetMapping
  Items<SettingView> list(RequestPrincipal principal) {
    return new Items<>(settings.list(principal.factoryId()));
  }

  /** Body {@code {"value": ...}}; the value is checked against the key's shape. */
  @RequirePermission(action = Action.SETTINGS_MANAGE, scope = ScopeType.FACTORY)
  @PutMapping("/{key}")
  SettingView update(
      @PathVariable String key, @RequestBody JsonNode body, RequestPrincipal principal) {
    JsonBody.of(body);
    return settings.set(principal.employeeId(), principal.factoryId(), key, body.get("value"));
  }
}
