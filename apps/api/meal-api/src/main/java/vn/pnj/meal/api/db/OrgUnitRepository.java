package vn.pnj.meal.api.db;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgUnitRepository extends JpaRepository<OrgUnit, String> {
  List<OrgUnit> findByFactoryId(String factoryId);
}
