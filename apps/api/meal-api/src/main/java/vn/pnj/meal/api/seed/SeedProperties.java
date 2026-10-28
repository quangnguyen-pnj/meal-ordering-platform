package vn.pnj.meal.api.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Development seed (never in production).
 *
 * @param initialPin SEED_INITIAL_PIN: every seeded employee starts with it and must change it
 * @param onStart SEED_ON_START: seed before serving (the Compose stack)
 */
@ConfigurationProperties("meal.seed")
public record SeedProperties(String initialPin, boolean onStart) {}
