package vn.pnj.meal.api.config;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Every controller is served under {@code /api}, which nginx forwards unchanged (plan section 6).
 * Controllers map their own path without the prefix, like the legacy global prefix.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
  public static final String API_PREFIX = "/api";

  @Override
  public void configurePathMatch(PathMatchConfigurer configurer) {
    configurer.addPathPrefix(
        API_PREFIX,
        type ->
            type.isAnnotationPresent(RestController.class)
                && !ErrorController.class.isAssignableFrom(type));
  }
}
