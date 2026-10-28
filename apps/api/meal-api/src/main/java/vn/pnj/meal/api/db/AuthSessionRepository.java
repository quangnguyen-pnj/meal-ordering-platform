package vn.pnj.meal.api.db;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AuthSessionRepository extends JpaRepository<AuthSession, String> {
  Optional<AuthSession> findByTokenHash(String tokenHash);

  @Modifying
  @Query("delete from AuthSession s where s.expiresAt < ?1")
  int deleteExpired(Instant now);

  @Modifying
  @Query("delete from AuthSession s where s.employeeId = ?1")
  int deleteByEmployee(String employeeId);

  @Modifying
  @Query("delete from AuthSession s where s.employeeId = ?1 and s.id <> ?2")
  int deleteByEmployeeExcept(String employeeId, String keepSessionId);
}
