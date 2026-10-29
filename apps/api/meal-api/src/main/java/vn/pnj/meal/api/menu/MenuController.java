package vn.pnj.meal.api.menu;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.pnj.meal.api.access.RequestPrincipal;
import vn.pnj.meal.api.access.RequirePermission;
import vn.pnj.meal.api.access.ScopeType;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.menu.MenuService.MenuSaveResult;
import vn.pnj.meal.api.menu.MenuService.MenuWeekView;
import vn.pnj.meal.api.menu.MenuService.MyMenuWeekView;
import vn.pnj.meal.rules.VnTime;
import vn.pnj.meal.rules.access.Action;
import vn.pnj.meal.rules.deadline.Deadlines;

/** /admin/menu: build, save and publish a canteen's weekly menu (HC admin, BR-11..BR-15). */
@RestController
public class MenuController {
  private final MenuService menus;
  private final ClockService clock;

  public MenuController(MenuService menus, ClockService clock) {
    this.menus = menus;
    this.clock = clock;
  }

  /** A calendar date {@code YYYY-MM-DD} that exists (no 2027-02-30); 400 otherwise. */
  public static LocalDate date(String value, String path) {
    try {
      return VnTime.parseDate(value);
    } catch (IllegalArgumentException e) {
      throw ApiException.invalid(
          "Request validation failed",
          List.of(java.util.Map.of("path", path, "message", "Invalid calendar date")));
    }
  }

  public record Canteens(List<MenuService.CanteenOption> items, LocalDate upcomingWeekStart) {}

  @RequirePermission(action = Action.MENU_MANAGE, scope = ScopeType.FACTORY)
  @GetMapping("/admin/menu/canteens")
  Canteens canteens(RequestPrincipal p) {
    return new Canteens(menus.canteens(p.factoryId()), Deadlines.upcomingWeekStart(clock.now()));
  }

  @RequirePermission(action = Action.MENU_MANAGE, scope = ScopeType.FACTORY)
  @GetMapping("/admin/menu/weeks")
  MenuWeekView week(
      @RequestParam @NotNull @Size(min = 1) String canteenId,
      @RequestParam String weekStart,
      RequestPrincipal p) {
    return menus.adminWeek(p.factoryId(), canteenId, date(weekStart, "weekStart"));
  }

  public record ItemBody(@Size(min = 1) String id, @NotNull @Size(min = 1) String dishId) {}

  public record DayBody(
      @NotNull String date,
      @NotNull Boolean isServing,
      @NotNull List<@NotNull @Valid ItemBody> items,
      @NotNull List<@NotNull @Size(min = 1) String> dessertIds) {}

  /** BR-11: the HC admin saves a whole week of one canteen at once. */
  public record WeekBody(
      @NotNull @Size(min = 1) String canteenId,
      @NotNull String weekStart,
      @NotNull @Size(min = 1, max = 7) List<@NotNull @Valid DayBody> days,
      @Size(max = 500) String reason) {
    public WeekBody {
      reason = reason == null ? null : reason.strip();
    }

    MenuService.WeekInput input() {
      List<MenuService.DayInput> parsed = new java.util.ArrayList<>();
      for (int i = 0; i < days.size(); i++) {
        DayBody d = days.get(i);
        parsed.add(
            new MenuService.DayInput(
                date(d.date(), "days." + i + ".date"),
                d.isServing(),
                d.items().stream().map(x -> new MenuService.ItemInput(x.id(), x.dishId())).toList(),
                d.dessertIds()));
      }
      return new MenuService.WeekInput(canteenId, date(weekStart, "weekStart"), parsed, reason);
    }
  }

  @RequirePermission(action = Action.MENU_MANAGE, scope = ScopeType.FACTORY)
  @PutMapping("/admin/menu/weeks")
  MenuSaveResult save(@Valid @RequestBody WeekBody body, RequestPrincipal p) {
    return menus.save(p.employeeId(), p.factoryId(), body.input());
  }

  /** Saves the submitted days and publishes in one step (no separate draft save needed). */
  @RequirePermission(action = Action.MENU_MANAGE, scope = ScopeType.FACTORY)
  @PostMapping("/admin/menu/weeks/publish")
  MenuWeekView saveAndPublish(@Valid @RequestBody WeekBody body, RequestPrincipal p) {
    return menus.saveAndPublish(p.employeeId(), p.factoryId(), body.input());
  }

  @RequirePermission(action = Action.MENU_MANAGE, scope = ScopeType.FACTORY)
  @PostMapping("/admin/menu/weeks/{id}/publish")
  MenuWeekView publish(@PathVariable String id, RequestPrincipal p) {
    return menus.publish(p.employeeId(), p.factoryId(), id);
  }

  /** The signed-in employee's published menu (BR-02, BR-15). */
  @RequirePermission(action = Action.MENU_REGISTER_OWN, scope = ScopeType.SELF)
  @GetMapping("/menu/my-week")
  MyMenuWeekView myWeek(@RequestParam(required = false) String weekStart, RequestPrincipal p) {
    LocalDate week =
        weekStart == null ? Deadlines.upcomingWeekStart(clock.now()) : date(weekStart, "weekStart");
    return menus.myWeek(p.employeeId(), p.factoryId(), week);
  }
}
