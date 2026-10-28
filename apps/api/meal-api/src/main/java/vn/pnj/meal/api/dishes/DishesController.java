package vn.pnj.meal.api.dishes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import vn.pnj.meal.api.access.RequestPrincipal;
import vn.pnj.meal.api.access.RequirePermission;
import vn.pnj.meal.api.access.ScopeType;
import vn.pnj.meal.api.common.JsonBody;
import vn.pnj.meal.api.common.Xlsx;
import vn.pnj.meal.api.dishes.DishesService.Archived;
import vn.pnj.meal.api.dishes.DishesService.DessertView;
import vn.pnj.meal.api.dishes.DishesService.DishTypeView;
import vn.pnj.meal.api.dishes.DishesService.DishView;
import vn.pnj.meal.api.dishes.DishesService.Id;
import vn.pnj.meal.api.dishes.DishesService.Library;
import vn.pnj.meal.api.dishes.DishesService.LibraryImportView;
import vn.pnj.meal.api.org.OrgController;
import vn.pnj.meal.rules.access.Action;
import vn.pnj.meal.rules.menu.MenuRules;

/**
 * /admin/dishes: each canteen's main dishes and desserts (BR-10), the dish types they use, and the
 * Excel import of a library. HC admin only.
 */
@RestController
@RequestMapping("/admin")
@RequirePermission(action = Action.DISHES_MANAGE, scope = ScopeType.FACTORY)
public class DishesController {
  private static final String CODE = "^[A-Za-z0-9._-]+$";
  private static final String HEX = "^#[0-9a-fA-F]{6}$";
  private static final String BOOL = "true|false";

  private final DishesService dishes;
  private final LibraryWorkbook workbook;

  public DishesController(DishesService dishes, LibraryWorkbook workbook) {
    this.dishes = dishes;
    this.workbook = workbook;
  }

  public record Items<T>(List<T> items) {}

  /** A library name: trimmed, 1–120 characters, then single-spaced (BR-10). */
  static String libraryName(String name) {
    return name == null ? null : MenuRules.normalizeLibraryName(name);
  }

  static String trim(String value) {
    return value == null ? null : value.strip();
  }

  // --- Dishes -----------------------------------------------------------------------------

  public record DishInput(
      @NotNull @Size(min = 1, max = 64) String canteenId,
      @NotNull @Size(min = 1, max = 120) String name,
      @NotNull @Size(min = 1, max = 64) String typeId,
      @NotEmpty List<@NotNull @Pattern(regexp = "LUNCH") String> sessions) {
    public DishInput {
      canteenId = trim(canteenId);
      name = libraryName(name);
      typeId = trim(typeId);
    }
  }

  public record DishUpdate(
      @NotNull @Size(min = 1, max = 120) String name,
      @NotNull @Size(min = 1, max = 64) String typeId,
      @NotEmpty List<@NotNull @Pattern(regexp = "LUNCH") String> sessions) {
    public DishUpdate {
      name = libraryName(name);
      typeId = trim(typeId);
    }
  }

  @GetMapping("/dishes")
  Items<DishView> listDishes(
      @RequestParam @NotNull @Size(min = 1) String canteenId,
      @RequestParam(required = false) @Pattern(regexp = BOOL) String includeArchived,
      RequestPrincipal p) {
    return new Items<>(
        dishes.listDishes(p.factoryId(), canteenId.strip(), "true".equals(includeArchived)));
  }

  @PostMapping("/dishes")
  @ResponseStatus(HttpStatus.CREATED)
  DishView createDish(@Valid @RequestBody DishInput body, RequestPrincipal p) {
    return dishes.createDish(
        p.employeeId(),
        p.factoryId(),
        new DishesService.DishInput(body.canteenId(), body.name(), body.typeId(), body.sessions()));
  }

  @PutMapping("/dishes/{id}")
  DishView updateDish(
      @PathVariable String id, @Valid @RequestBody DishUpdate body, RequestPrincipal p) {
    return dishes.updateDish(
        p.employeeId(),
        p.factoryId(),
        id,
        new DishesService.DishInput(null, body.name(), body.typeId(), body.sessions()));
  }

  @PostMapping("/dishes/{id}/archive")
  Archived archiveDish(@PathVariable String id, RequestPrincipal p) {
    return dishes.setArchived(Library.DISH, p.employeeId(), p.factoryId(), id, true);
  }

  @PostMapping("/dishes/{id}/restore")
  Archived restoreDish(@PathVariable String id, RequestPrincipal p) {
    return dishes.setArchived(Library.DISH, p.employeeId(), p.factoryId(), id, false);
  }

  // --- Desserts ---------------------------------------------------------------------------

  public record DessertInput(
      @NotNull @Size(min = 1, max = 64) String canteenId,
      @NotNull @Size(min = 1, max = 120) String name) {
    public DessertInput {
      canteenId = trim(canteenId);
      name = libraryName(name);
    }
  }

  public record DessertUpdate(@NotNull @Size(min = 1, max = 120) String name) {
    public DessertUpdate {
      name = libraryName(name);
    }
  }

  @GetMapping("/desserts")
  Items<DessertView> listDesserts(
      @RequestParam @NotNull @Size(min = 1) String canteenId,
      @RequestParam(required = false) @Pattern(regexp = BOOL) String includeArchived,
      RequestPrincipal p) {
    return new Items<>(
        dishes.listDesserts(p.factoryId(), canteenId.strip(), "true".equals(includeArchived)));
  }

  @PostMapping("/desserts")
  @ResponseStatus(HttpStatus.CREATED)
  Id createDessert(@Valid @RequestBody DessertInput body, RequestPrincipal p) {
    return dishes.createDessert(
        p.employeeId(),
        p.factoryId(),
        new DishesService.DessertInput(body.canteenId(), body.name()));
  }

  @PutMapping("/desserts/{id}")
  Id updateDessert(
      @PathVariable String id, @Valid @RequestBody DessertUpdate body, RequestPrincipal p) {
    return dishes.updateDessert(p.employeeId(), p.factoryId(), id, body.name());
  }

  @PostMapping("/desserts/{id}/archive")
  Archived archiveDessert(@PathVariable String id, RequestPrincipal p) {
    return dishes.setArchived(Library.DESSERT, p.employeeId(), p.factoryId(), id, true);
  }

  @PostMapping("/desserts/{id}/restore")
  Archived restoreDessert(@PathVariable String id, RequestPrincipal p) {
    return dishes.setArchived(Library.DESSERT, p.employeeId(), p.factoryId(), id, false);
  }

  // --- Dish types -------------------------------------------------------------------------

  /** BR-10 / BR-13: e.g. Mặn, Chay, Cháo, Eat clean, with a colour and usual bounds per day. */
  public record DishTypeInput(
      @NotNull @Size(min = 1, max = 32) @Pattern(regexp = CODE) String code,
      @NotNull @Size(min = 1, max = 40) String name,
      @NotNull @Pattern(regexp = HEX) String color,
      @Min(0) @Max(9999) Integer sortOrder,
      @Min(0) @Max(20) Integer minPerDay,
      @Min(0) @Max(20) Integer maxPerDay) {
    public DishTypeInput {
      code = trim(code);
      name = trim(name);
      color = color == null ? null : color.strip().toLowerCase(Locale.ROOT);
      sortOrder = sortOrder == null ? 0 : sortOrder;
    }
  }

  @GetMapping("/dish-types")
  Items<DishTypeView> listTypes(RequestPrincipal p) {
    return new Items<>(dishes.listTypes(p.factoryId()));
  }

  @PostMapping("/dish-types")
  @ResponseStatus(HttpStatus.CREATED)
  Id createType(@Valid @RequestBody DishTypeInput body, RequestPrincipal p) {
    if (body.minPerDay() != null
        && body.maxPerDay() != null
        && body.minPerDay() > body.maxPerDay()) {
      JsonBody.fail("maxPerDay", "maxPerDay must be at least minPerDay");
    }
    return dishes.createType(
        p.employeeId(),
        p.factoryId(),
        new DishesService.TypeInput(
            body.code(),
            body.name(),
            body.color(),
            body.sortOrder(),
            body.minPerDay(),
            body.maxPerDay()));
  }

  /** {@code {name?, color?, sortOrder?, minPerDay?, maxPerDay?, active?}}; bounds may be null. */
  @PatchMapping("/dish-types/{id}")
  Id updateType(@PathVariable String id, @RequestBody JsonNode node, RequestPrincipal p) {
    JsonBody body = JsonBody.of(node);
    String name = body.string("name", 1, 40);
    String color = body.string("color", 1, 7, java.util.regex.Pattern.compile(HEX));
    Integer sortOrder = body.integer("sortOrder", 0, 9999);
    boolean setMin = body.has("minPerDay");
    Integer min = setMin ? body.nullableInteger("minPerDay", 0, 20) : null;
    boolean setMax = body.has("maxPerDay");
    Integer max = setMax ? body.nullableInteger("maxPerDay", 0, 20) : null;
    Boolean active = body.bool("active");
    body.requireValid();
    return dishes.updateType(
        p.employeeId(),
        p.factoryId(),
        id,
        new DishesService.TypePatch(
            name,
            color == null ? null : color.toLowerCase(Locale.ROOT),
            sortOrder,
            setMin,
            min,
            setMax,
            max,
            active));
  }

  // --- Excel ------------------------------------------------------------------------------

  @GetMapping("/dish-library/template")
  ResponseEntity<byte[]> template(RequestPrincipal p) {
    return Xlsx.download(workbook.template(p.factoryId()), "mau-thu-vien-mon.xlsx");
  }

  /** Validates the file (preview) or, with {@code apply=true}, adds its new entries at once. */
  @PostMapping("/dish-library/import")
  LibraryImportView importLibrary(
      @RequestParam @NotNull @Size(min = 1) String canteenId,
      @RequestParam(required = false) @Pattern(regexp = BOOL) String apply,
      @RequestParam(name = "file", required = false) MultipartFile file,
      RequestPrincipal p) {
    byte[] content = OrgController.bytes(file);
    return dishes.importLibrary(
        p.employeeId(),
        p.factoryId(),
        canteenId.strip(),
        workbook.parse(content),
        "true".equals(apply));
  }
}
