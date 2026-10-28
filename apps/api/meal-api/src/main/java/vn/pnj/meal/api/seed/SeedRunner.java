package vn.pnj.meal.api.seed;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs the seed when the API starts with SEED_ON_START=true (the Compose stack) or as the {@code
 * seed} command ({@code java -jar meal-api.jar seed}, see {@code MealApiApplication}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SeedRunner implements ApplicationRunner {
  public static final String COMMAND = "seed";

  private final SeedService seed;
  private final SeedProperties properties;

  public SeedRunner(SeedService seed, SeedProperties properties) {
    this.seed = seed;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (properties.onStart() || args.getNonOptionArgs().contains(COMMAND)) {
      seed.run();
    }
  }
}
