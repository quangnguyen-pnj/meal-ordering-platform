package vn.pnj.meal.api.clock;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** True when fake time may be used: CLOCK_FAKE_ENABLED outside production. */
public class FakeTimeAllowedCondition implements Condition {
  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    Binder binder = Binder.get(context.getEnvironment());
    boolean enabled = binder.bind("meal.clock-fake-enabled", Boolean.class).orElse(false);
    String environment = binder.bind("meal.environment", String.class).orElse("development").trim();
    return enabled && !"production".equalsIgnoreCase(environment);
  }
}
