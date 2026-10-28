package vn.pnj.meal.api.db;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.pnj.meal.api.db.Enums.AssignableRole;

public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, String> {
  List<RoleAssignment> findByEmployeeId(String employeeId);

  List<RoleAssignment> findByEmployeeIdAndRevokedAtIsNull(String employeeId);

  List<RoleAssignment> findByRoleInAndRevokedAtIsNullOrderByRoleAscCreatedAtAsc(
      Collection<AssignableRole> roles);

  long countByRoleAndRevokedAtIsNull(AssignableRole role);
}
