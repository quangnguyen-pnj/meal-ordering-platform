package vn.pnj.meal.api.auth.entra;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.pnj.meal.api.config.AppProperties;

@Configuration
public class EntraConfig {

  @Bean
  EntraProvider entraProvider(AppProperties properties) {
    AppProperties.Entra entra = properties.entra();
    return entra.mode() == AppProperties.EntraMode.OIDC
        ? OidcEntraProvider.forTenant(
            entra.tenantId(), entra.clientId(), entra.clientSecret(), entra.redirectUri())
        : new MockEntraProvider();
  }
}
