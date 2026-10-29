package vn.pnj.meal.api.org;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
import vn.pnj.meal.api.employees.EmployeeMappingView;
import vn.pnj.meal.api.org.MappingImportService.MappingImportView;
import vn.pnj.meal.api.org.OrgService.OrgUnitView;
import vn.pnj.meal.rules.access.Action;

/** /admin/org: meal-unit flags, mapping, dept admins, lists, Excel import/export (BR-60..BR-64). */
@RestController
@RequestMapping("/admin/org")
public class OrgController {
  private final OrgService org;
  private final MappingImportService imports;
  private final OrgSyncService sync;

  public OrgController(OrgService org, MappingImportService imports, OrgSyncService sync) {
    this.org = org;
    this.imports = imports;
    this.sync = sync;
  }

  public record Items<T>(List<T> items) {}

  @RequirePermission(action = Action.ORG_MANAGE, scope = ScopeType.FACTORY)
  @GetMapping("/tree")
  Items<OrgUnitView> tree(RequestPrincipal p) {
    return new Items<>(org.tree(p.factoryId()));
  }

  /**
   * BR-60 / BR-62: {@code {isMealUnit?, floorId?, canteenId?, costCenterId?}}; an id set to null
   * clears the node's own value so it inherits again.
   */
  @RequirePermission(action = Action.ORG_MANAGE, scope = ScopeType.ORG_UNIT_PARAM, param = "id")
  @PatchMapping("/units/{id}")
  OrgService.UnitUpdated updateUnit(
      @PathVariable String id, @RequestBody JsonNode node, RequestPrincipal p) {
    JsonBody body = JsonBody.of(node);
    Boolean isMealUnit = body.bool("isMealUnit");
    boolean setFloor = body.has("floorId");
    boolean setCanteen = body.has("canteenId");
    boolean setCostCenter = body.has("costCenterId");
    String floorId = setFloor ? body.nullableId("floorId") : null;
    String canteenId = setCanteen ? body.nullableId("canteenId") : null;
    String costCenterId = setCostCenter ? body.nullableId("costCenterId") : null;
    body.refine(
        body.has("isMealUnit") || setFloor || setCanteen || setCostCenter, "", "Nothing to update");
    body.requireValid();
    return org.updateUnit(
        p.employeeId(),
        p.factoryId(),
        id,
        new OrgService.UnitPatch(
            isMealUnit, setFloor, floorId, setCanteen, canteenId, setCostCenter, costCenterId));
  }

  public record DeptAdminBody(@NotNull @Size(min = 1, max = 32) String employeeCode) {
    public DeptAdminBody {
      employeeCode = employeeCode == null ? null : employeeCode.strip();
    }
  }

  @RequirePermission(action = Action.ORG_MANAGE, scope = ScopeType.ORG_UNIT_PARAM, param = "id")
  @PostMapping("/units/{id}/dept-admins")
  @ResponseStatus(HttpStatus.CREATED)
  OrgService.DeptAdminAssigned assignDeptAdmin(
      @PathVariable String id, @Valid @RequestBody DeptAdminBody body, RequestPrincipal p) {
    return org.assignDeptAdmin(p.employeeId(), p.factoryId(), id, body.employeeCode());
  }

  @RequirePermission(action = Action.ORG_MANAGE, scope = ScopeType.FACTORY)
  @PostMapping("/dept-admins/{assignmentId}/revoke")
  OrgService.DeptAdminRevoked revokeDeptAdmin(
      @PathVariable String assignmentId, RequestPrincipal p) {
    return org.revokeDeptAdmin(p.employeeId(), p.factoryId(), assignmentId);
  }

  @RequirePermission(action = Action.ORG_MANAGE, scope = ScopeType.FACTORY)
  @GetMapping("/unassigned")
  Items<EmployeeMappingView> unassigned(RequestPrincipal p) {
    return new Items<>(org.unassigned(p.factoryId()));
  }

  @RequirePermission(action = Action.ORG_MANAGE, scope = ScopeType.FACTORY)
  @GetMapping("/missing-cost-center")
  Items<EmployeeMappingView> missingCostCenter(RequestPrincipal p) {
    return new Items<>(org.missingCostCenter(p.factoryId()));
  }

  /** BR-64: export uses the import template. */
  @RequirePermission(action = Action.ORG_MANAGE, scope = ScopeType.FACTORY)
  @GetMapping("/mapping/export")
  ResponseEntity<byte[]> exportMapping(RequestPrincipal p) {
    return Xlsx.download(imports.exportWorkbook(p.factoryId()), "mapping-to-chuc.xlsx");
  }

  /** BR-64: upload validates every row; one invalid row rejects the file. Nothing applied yet. */
  @RequirePermission(action = Action.ORG_MANAGE, scope = ScopeType.FACTORY)
  @PostMapping("/mapping/imports")
  @ResponseStatus(HttpStatus.CREATED)
  MappingImportView upload(
      @RequestParam(name = "file", required = false) MultipartFile file, RequestPrincipal p) {
    return imports.upload(
        p.employeeId(),
        p.factoryId(),
        file == null ? null : file.getOriginalFilename(),
        bytes(file));
  }

  /** The uploaded file's content, or VALIDATION_FAILED when no file came with the request. */
  public static byte[] bytes(MultipartFile file) {
    if (file == null) {
      throw Xlsx.fileError("File is required", "FILE_REQUIRED");
    }
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @RequirePermission(action = Action.ORG_MANAGE, scope = ScopeType.FACTORY)
  @GetMapping("/mapping/imports/{id}")
  MappingImportView importView(@PathVariable String id, RequestPrincipal p) {
    return imports.view(id, p.factoryId());
  }

  /** BR-64: all-or-nothing apply of a previewed upload. */
  @RequirePermission(action = Action.ORG_MANAGE, scope = ScopeType.FACTORY)
  @PostMapping("/mapping/imports/{id}/apply")
  MappingImportView apply(@PathVariable String id, RequestPrincipal p) {
    return imports.apply(p.employeeId(), p.factoryId(), id);
  }

  /** Plan section 5: manual trigger of the org + employee sync (also runs daily at 02:00). */
  @RequirePermission(action = Action.SYNC_MANAGE, scope = ScopeType.FACTORY)
  @PostMapping("/sync")
  OrgSyncService.Result runSync(RequestPrincipal p) {
    return sync.runOrThrow(p.factoryId(), p.employeeId());
  }
}
