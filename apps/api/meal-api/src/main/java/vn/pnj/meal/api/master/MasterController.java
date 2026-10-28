package vn.pnj.meal.api.master;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.pnj.meal.api.access.RequestPrincipal;
import vn.pnj.meal.api.access.RequirePermission;
import vn.pnj.meal.api.access.ScopeType;
import vn.pnj.meal.api.common.JsonBody;
import vn.pnj.meal.api.master.MasterService.Created;
import vn.pnj.meal.api.master.MasterService.Kind;
import vn.pnj.meal.api.master.MasterService.MasterItemView;
import vn.pnj.meal.rules.access.Action;

/** /admin/master: canteens, floors, cost centers (BR-62). HC admin and system admin. */
@RestController
@RequestMapping("/admin/master")
@RequirePermission(action = Action.ORG_MANAGE, scope = ScopeType.FACTORY)
public class MasterController {
  static final String CODE = "^[A-Za-z0-9._-]+$";

  private final MasterService master;

  public MasterController(MasterService master) {
    this.master = master;
  }

  public record Items<T>(List<T> items) {}

  /** Canteens and cost centers: code and name. */
  public record CodeName(
      @NotNull @Size(min = 1, max = 32) @Pattern(regexp = CODE) String code,
      @NotNull @Size(min = 1, max = 120) String name) {
    public CodeName {
      code = code == null ? null : code.strip();
      name = name == null ? null : name.strip();
    }
  }

  public record FloorInput(
      @NotNull @Size(min = 1, max = 32) @Pattern(regexp = CODE) String code,
      @NotNull @Size(min = 1, max = 120) String name,
      @Min(0) @Max(9999) Integer sortOrder) {
    public FloorInput {
      code = code == null ? null : code.strip();
      name = name == null ? null : name.strip();
      sortOrder = sortOrder == null ? 0 : sortOrder;
    }
  }

  @GetMapping("/canteens")
  Items<MasterItemView> canteens(RequestPrincipal p) {
    return new Items<>(master.list(Kind.CANTEEN, p.factoryId()));
  }

  @PostMapping("/canteens")
  @ResponseStatus(HttpStatus.CREATED)
  Created createCanteen(@Valid @RequestBody CodeName body, RequestPrincipal p) {
    return master.create(
        Kind.CANTEEN,
        p.employeeId(),
        p.factoryId(),
        new MasterService.Input(body.code(), body.name(), null));
  }

  @PatchMapping("/canteens/{id}")
  Created updateCanteen(@PathVariable String id, @RequestBody JsonNode body, RequestPrincipal p) {
    return master.update(Kind.CANTEEN, p.employeeId(), p.factoryId(), id, patch(body, false));
  }

  @GetMapping("/floors")
  Items<MasterItemView> floors(RequestPrincipal p) {
    return new Items<>(master.list(Kind.FLOOR, p.factoryId()));
  }

  @PostMapping("/floors")
  @ResponseStatus(HttpStatus.CREATED)
  Created createFloor(@Valid @RequestBody FloorInput body, RequestPrincipal p) {
    return master.create(
        Kind.FLOOR,
        p.employeeId(),
        p.factoryId(),
        new MasterService.Input(body.code(), body.name(), body.sortOrder()));
  }

  @PatchMapping("/floors/{id}")
  Created updateFloor(@PathVariable String id, @RequestBody JsonNode body, RequestPrincipal p) {
    return master.update(Kind.FLOOR, p.employeeId(), p.factoryId(), id, patch(body, true));
  }

  @GetMapping("/cost-centers")
  Items<MasterItemView> costCenters(RequestPrincipal p) {
    return new Items<>(master.list(Kind.COST_CENTER, p.factoryId()));
  }

  @PostMapping("/cost-centers")
  @ResponseStatus(HttpStatus.CREATED)
  Created createCostCenter(@Valid @RequestBody CodeName body, RequestPrincipal p) {
    return master.create(
        Kind.COST_CENTER,
        p.employeeId(),
        p.factoryId(),
        new MasterService.Input(body.code(), body.name(), null));
  }

  @PatchMapping("/cost-centers/{id}")
  Created updateCostCenter(
      @PathVariable String id, @RequestBody JsonNode body, RequestPrincipal p) {
    return master.update(Kind.COST_CENTER, p.employeeId(), p.factoryId(), id, patch(body, false));
  }

  /** {@code {name?, active?}}, plus {@code sortOrder?} for floors; other fields are ignored. */
  private static MasterService.Patch patch(JsonNode node, boolean floor) {
    JsonBody body = JsonBody.of(node);
    String name = body.string("name", 1, 120);
    Integer sortOrder = floor ? body.integer("sortOrder", 0, 9999) : null;
    Boolean active = body.bool("active");
    body.requireValid();
    return new MasterService.Patch(name, sortOrder, active);
  }
}
