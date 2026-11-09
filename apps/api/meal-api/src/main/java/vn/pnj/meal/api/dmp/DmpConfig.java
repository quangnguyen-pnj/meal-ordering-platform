package vn.pnj.meal.api.dmp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;
import vn.pnj.meal.api.clock.ClockService;
import vn.pnj.meal.api.config.AppProperties;

/** DMP_MODE=mock serves the seed scenarios; http calls the real DMP (plan section 5). */
@Configuration
public class DmpConfig {

  @Bean
  DmpClient dmpClient(AppProperties properties, JsonMapper json, ClockService clock) {
    AppProperties.Dmp dmp = properties.dmp();
    return dmp.mode() == AppProperties.DmpMode.HTTP
        ? new HttpDmpClient(dmp)
        : new MockDmpClient(dmp.mockScenario(), json, clock::now);
  }
}
