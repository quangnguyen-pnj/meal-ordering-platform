package vn.pnj.meal.api.health;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.pnj.meal.api.access.Public;

/** Liveness for Docker and nginx: answers only when the database answers. */
@RestController
public class HealthController {
  private final JdbcClient jdbc;

  public HealthController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Public
  @GetMapping("/health")
  Map<String, String> check() {
    jdbc.sql("SELECT 1").query(Integer.class).single();
    return Map.of("status", "ok");
  }
}
