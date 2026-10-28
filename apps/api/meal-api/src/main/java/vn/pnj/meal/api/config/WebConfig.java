package vn.pnj.meal.api.config;

import java.util.List;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import vn.pnj.meal.api.access.AccessArgumentResolver;
import vn.pnj.meal.api.access.AccessInterceptor;

/**
 * Every controller is served under {@code /api}, which nginx forwards unchanged (plan section 6).
 * Controllers map their own path without the prefix, like the legacy global prefix.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
  public static final String API_PREFIX = "/api";

  private final AccessInterceptor access;
  private final AccessArgumentResolver accessArguments;

  public WebConfig(AccessInterceptor access, AccessArgumentResolver accessArguments) {
    this.access = access;
    this.accessArguments = accessArguments;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(access);
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(accessArguments);
  }

  @Override
  public void configurePathMatch(PathMatchConfigurer configurer) {
    configurer.addPathPrefix(
        API_PREFIX,
        type ->
            type.isAnnotationPresent(RestController.class)
                && type.getPackageName().startsWith("vn.pnj.meal")
                && !ErrorController.class.isAssignableFrom(type));
  }
}
