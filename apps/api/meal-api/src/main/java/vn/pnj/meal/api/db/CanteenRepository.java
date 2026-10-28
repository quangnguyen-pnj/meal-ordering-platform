package vn.pnj.meal.api.db;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CanteenRepository extends JpaRepository<Canteen, String> {
  List<Canteen> findByFactoryId(String factoryId);
}
