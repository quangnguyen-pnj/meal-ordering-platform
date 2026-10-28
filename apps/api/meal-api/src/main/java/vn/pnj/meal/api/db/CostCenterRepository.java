package vn.pnj.meal.api.db;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CostCenterRepository extends JpaRepository<CostCenter, String> {
  List<CostCenter> findByFactoryId(String factoryId);
}
