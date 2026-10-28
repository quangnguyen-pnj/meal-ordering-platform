package vn.pnj.meal.api.access;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import vn.pnj.meal.rules.access.ListScope;

/**
 * Hands a controller the {@link RequestPrincipal} and, on LIST routes, the {@link ListScope} that
 * {@link AccessInterceptor} established. Fails loudly on a route that has neither.
 */
@Component
public class AccessArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    Class<?> type = parameter.getParameterType();
    return type == RequestPrincipal.class || type == ListScope.class;
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    String key =
        parameter.getParameterType() == RequestPrincipal.class
            ? AccessInterceptor.PRINCIPAL
            : AccessInterceptor.LIST_SCOPE;
    Object value = webRequest.getAttribute(key, RequestAttributes.SCOPE_REQUEST);
    if (value == null) {
      throw new IllegalStateException(
          parameter.getParameterType().getSimpleName() + " used on a route that does not set it");
    }
    return value;
  }
}
