package vn.pnj.meal.api.master;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.pnj.meal.api.access.RequestPrincipal;
import vn.pnj.meal.api.access.RequirePermission;
import vn.pnj.meal.api.access.ScopeType;
import vn.pnj.meal.api.master.MasterService.SupplierView;
import vn.pnj.meal.rules.access.Action;

/** Caterer name and unit price, part of business settings (plan section 3). */
@RestController
@RequestMapping("/admin/supplier")
@RequirePermission(action = Action.SETTINGS_MANAGE, scope = ScopeType.FACTORY)
public class SupplierController {
  private final MasterService master;

  public SupplierController(MasterService master) {
    this.master = master;
  }

  public record SupplierUpdate(
      @NotNull @Size(min = 1, max = 120) String name,
      @NotNull @DecimalMin("0") @DecimalMax("10000000") BigDecimal unitPrice) {
    public SupplierUpdate {
      name = name == null ? null : name.strip();
    }
  }

  @GetMapping
  SupplierView get(RequestPrincipal p) {
    return master.supplier(p.factoryId());
  }

  @PutMapping
  SupplierView update(@Valid @RequestBody SupplierUpdate body, RequestPrincipal p) {
    return master.updateSupplier(p.employeeId(), p.factoryId(), body.name(), body.unitPrice());
  }
}
