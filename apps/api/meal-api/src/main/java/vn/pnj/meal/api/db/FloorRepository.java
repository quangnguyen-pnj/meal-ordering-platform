package vn.pnj.meal.api.db;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FloorRepository extends JpaRepository<Floor, String> {
  List<Floor> findByFactoryId(String factoryId);
}
