package vn.pnj.meal.api.employees;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.pnj.meal.api.access.RequestPrincipal;
import vn.pnj.meal.api.access.RequirePermission;
import vn.pnj.meal.api.access.ScopeType;
import vn.pnj.meal.api.auth.PinAuthService;
import vn.pnj.meal.api.org.OrgSnapshot;
import vn.pnj.meal.rules.access.Action;
import vn.pnj.meal.rules.access.ListScope;
import vn.pnj.meal.rules.org.Mapping.MealStatus;

@RestController
@RequestMapping("/employees")
public class EmployeesController {
  private final EmployeesService employees;
  private final PinAuthService pinAuth;

  public EmployeesController(EmployeesService employees, PinAuthService pinAuth) {
    this.employees = employees;
    this.pinAuth = pinAuth;
  }

  /**
   * /admin/employees finder. Gated by the closest matrix row ("Reset PIN": units / factory /
   * system).
   */
  @RequirePermission(action = Action.PIN_RESET, scope = ScopeType.LIST)
  @GetMapping
  EmployeesService.Page list(
      @RequestParam(required = false) @Size(max = 64) String q,
      @RequestParam(required = false) @Size(min = 1) String orgUnitId,
      @RequestParam(required = false) @Size(min = 1) String floorId,
      @RequestParam(required = false) @Size(min = 1) String canteenId,
      @RequestParam(required = false) @Size(min = 1) String costCenterId,
      @RequestParam(required = false) MealStatus mealStatus,
      @RequestParam(required = false) @Pattern(regexp = "ACTIVE|LEFT") String status,
      @RequestParam(defaultValue = "0") @Min(0) int offset,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
      ListScope scope,
      RequestPrincipal principal) {
    return employees.list(
        scope,
        principal.factoryId(),
        new EmployeesService.Filter(
            q == null ? null : q.trim(),
            orgUnitId,
            floorId,
            canteenId,
            costCenterId,
            mealStatus,
            status,
            offset,
            limit));
  }

  public record OrgUnitOptions(List<OrgSnapshot.FilterOption> items) {}

  /** Khối / Trung tâm / Phòng ban filter options for the finder, within the caller's scope. */
  @RequirePermission(action = Action.PIN_RESET, scope = ScopeType.LIST)
  @GetMapping("/org-units")
  OrgUnitOptions orgUnits(ListScope scope, RequestPrincipal principal) {
    return new OrgUnitOptions(employees.orgUnits(scope, principal.factoryId()));
  }

  public record ResetPinBody(@Size(max = 500) String reason) {
    public ResetPinBody {
      reason = reason == null ? null : reason.trim();
    }
  }

  public record ResetPinResult(String temporaryPin) {}

  @RequirePermission(action = Action.PIN_RESET, scope = ScopeType.EMPLOYEE_PARAM, param = "code")
  @PostMapping("/{code}/pin/reset")
  ResetPinResult resetPin(
      @PathVariable String code,
      @Valid @RequestBody(required = false) ResetPinBody body,
      RequestPrincipal principal) {
    String reason = body == null ? null : body.reason();
    return new ResetPinResult(pinAuth.resetPin(principal.employeeId(), code, reason));
  }

  public record FloorOverrideBody(
      @Size(min = 1) String floorId, @NotNull @Size(min = 1, max = 500) String reason) {
    public FloorOverrideBody {
      reason = reason == null ? null : reason.trim();
    }
  }

  /** BR-63: pick-up floor of one employee; part of "manage ... mapping" (HC admin). */
  @RequirePermission(action = Action.ORG_MANAGE, scope = ScopeType.EMPLOYEE_PARAM, param = "code")
  @PutMapping("/{code}/floor-override")
  EmployeesService.Changed setFloorOverride(
      @PathVariable String code,
      @Valid @RequestBody FloorOverrideBody body,
      RequestPrincipal principal) {
    return employees.setFloorOverride(
        principal.employeeId(), principal.factoryId(), code, body.floorId(), body.reason());
  }
}
