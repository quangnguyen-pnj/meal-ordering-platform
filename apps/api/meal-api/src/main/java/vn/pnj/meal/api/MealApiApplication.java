package vn.pnj.meal.api;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import vn.pnj.meal.api.seed.SeedRunner;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MealApiApplication {
  /** Serves the API; {@code seed} as the first argument seeds the database and exits instead. */
  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(MealApiApplication.class);
    if (Arrays.asList(args).contains(SeedRunner.COMMAND)) {
      app.setWebApplicationType(WebApplicationType.NONE);
      System.exit(SpringApplication.exit(app.run(args)));
    }
    app.run(args);
  }
}
