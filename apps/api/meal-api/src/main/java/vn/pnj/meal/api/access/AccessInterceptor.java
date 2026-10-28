package vn.pnj.meal.api.access;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import vn.pnj.meal.api.auth.SessionService;
import vn.pnj.meal.api.common.ApiErrorCode;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.rules.access.Access;
import vn.pnj.meal.rules.access.ListScope;
import vn.pnj.meal.rules.access.ResourceScope;

/**
 * Authentication, the forced PIN change and the (role, action, scope) check of plan section 3, for
 * every controller method. Permissions are NOT checked inside handlers. A method that declares none
 * of {@link Public}, {@link Authenticated} or {@link RequirePermission} is denied.
 */
@Component
public class AccessInterceptor implements HandlerInterceptor {
  public static final String PRINCIPAL = AccessInterceptor.class.getName() + ".principal";
  public static final String LIST_SCOPE = AccessInterceptor.class.getName() + ".listScope";
  private static final Logger log = LoggerFactory.getLogger(AccessInterceptor.class);

  private final SessionService sessions;
  private final PrincipalService principals;
  private final ScopeResolver scopes;

  public AccessInterceptor(
      SessionService sessions, PrincipalService principals, ScopeResolver scopes) {
    this.sessions = sessions;
    this.principals = principals;
    this.scopes = scopes;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true; // not a controller: unknown routes end as 404
    }
    if (method.getBeanType().getPackageName().startsWith("org.springdoc")) {
      return true; // API docs, only switched on outside production (application.yml)
    }
    if (declared(method, Public.class)) {
      return true;
    }

    RequestPrincipal principal = authenticate(request);
    request.setAttribute(PRINCIPAL, principal);

    if (principal.mustChangePin() && !declared(method, AllowPinChange.class)) {
      throw new ApiException(
          HttpStatus.FORBIDDEN, ApiErrorCode.PIN_CHANGE_REQUIRED, "PIN change required");
    }

    RequirePermission permission =
        AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequirePermission.class);
    if (permission == null) {
      permission =
          AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequirePermission.class);
    }
    if (permission == null) {
      if (declared(method, Authenticated.class)) {
        return true;
      }
      log.error(
          "Route {} {} has no access annotation; denying (fail closed)",
          request.getMethod(),
          request.getRequestURI());
      throw deny();
    }

    if (permission.scope() == ScopeType.LIST) {
      if (!Access.hasAnyGrant(principal.access(), permission.action())) {
        throw deny();
      }
      ListScope listScope = Access.resolveListScope(principal.access(), permission.action());
      if (listScope instanceof ListScope.None) {
        throw deny();
      }
      request.setAttribute(LIST_SCOPE, listScope);
      return true;
    }

    Optional<ResourceScope> scope =
        scopes.resolve(permission.scope(), pathVariable(request, permission.param()), principal);
    if (scope.isEmpty()) {
      // Only callers who could see the whole population learn that a resource does not exist;
      // everyone else gets 403 so existence is not leaked outside their scope.
      if (principal.seesWholePopulation()) {
        throw ApiException.notFound("Not found");
      }
      throw deny();
    }
    if (!Access.authorize(principal.access(), permission.action(), scope.get())) {
      throw deny();
    }
    return true;
  }

  private RequestPrincipal authenticate(HttpServletRequest request) {
    String token = null;
    if (request.getCookies() != null) {
      for (Cookie cookie : request.getCookies()) {
        if (SessionService.COOKIE.equals(cookie.getName()) && !cookie.getValue().isEmpty()) {
          token = cookie.getValue();
        }
      }
    }
    RequestPrincipal principal =
        token == null
            ? null
            : sessions
                .findActive(token)
                .map(s -> principals.load(s.employeeId(), s.id(), s.method()))
                .orElse(null);
    if (principal == null) {
      throw new ApiException(
          HttpStatus.UNAUTHORIZED, ApiErrorCode.UNAUTHENTICATED, "Sign in required");
    }
    return principal;
  }

  private static boolean declared(
      HandlerMethod method, Class<? extends java.lang.annotation.Annotation> annotation) {
    return AnnotatedElementUtils.hasAnnotation(method.getMethod(), annotation)
        || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), annotation);
  }

  @SuppressWarnings("unchecked")
  private static String pathVariable(HttpServletRequest request, String name) {
    Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    return vars instanceof Map<?, ?> map ? ((Map<String, String>) map).get(name) : null;
  }

  private static ApiException deny() {
    return ApiException.forbidden("Access denied");
  }
}
