package vn.pnj.meal.api.access;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import vn.pnj.meal.rules.access.Action;

/**
 * Plan section 3: a route is guarded by a (role, action, scope) triple, checked by {@link
 * AccessInterceptor} before the handler runs. A route with none of {@link Public}, {@link
 * Authenticated} or this annotation is denied (fail closed).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequirePermission {
  Action action();

  ScopeType scope();

  /** Path variable holding the employee code or org unit id, for the *_PARAM scopes. */
  String param() default "";
}
