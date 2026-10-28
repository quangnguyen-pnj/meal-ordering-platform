package vn.pnj.meal.api.db;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmployeeRepository extends JpaRepository<Employee, String> {
  Optional<Employee> findByEmployeeCode(String employeeCode);

  /** Case-insensitive email match for Entra sign-in (plan section 5); at most two are needed. */
  @Query(
      nativeQuery = true,
      value =
          "SELECT * FROM \"Employee\" WHERE lower(email) = lower(?1) AND status = 'ACTIVE' LIMIT 2")
  List<Employee> findActiveByEmailIgnoreCase(String email);
}
