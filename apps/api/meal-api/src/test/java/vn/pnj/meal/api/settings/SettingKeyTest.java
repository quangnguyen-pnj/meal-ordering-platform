package vn.pnj.meal.api.settings;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Port of the "settings (plan 6.4)" part of packages/shared/src/rules/misc.test.ts. */
@DisplayName("settings (plan 6.4)")
class SettingKeyTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  @DisplayName("every default validates against its own shape")
  void defaultsValid() {
    for (SettingKey key : SettingKey.values()) {
      assertThat(key.accepts(JSON.readTree(key.defaultJson()))).as(key.code()).isTrue();
    }
  }

  @Test
  @DisplayName("BR-03: registration.close defaults to Thursday 23:59")
  void closeDefault() {
    assertThat(JSON.readTree(SettingKey.REGISTRATION_CLOSE.defaultJson()))
        .isEqualTo(JSON.readTree("{\"weekday\":4,\"time\":\"23:59\"}"));
  }

  @Test
  @DisplayName("holds the 17 documented keys")
  void keys() {
    assertThat(SettingKey.values()).hasSize(17);
    assertThat(SettingKey.fromCode("pin.maxAttempts")).isEqualTo(SettingKey.PIN_MAX_ATTEMPTS);
    assertThat(SettingKey.fromCode("nope")).isNull();
  }

  @Test
  @DisplayName("rejects malformed values")
  void malformed() {
    assertThat(SettingKey.DAY_LOCK_TIME.accepts(JSON.readTree("\"8:00\""))).isFalse();
    assertThat(SettingKey.DELIVERY_TOLERANCE_PCT.accepts(JSON.readTree("150"))).isFalse();
    assertThat(SettingKey.PIN_MAX_ATTEMPTS.accepts(JSON.readTree("0"))).isFalse();
    assertThat(SettingKey.SERVING_DAYS.accepts(JSON.readTree("[]"))).isFalse();
    assertThat(SettingKey.SERVING_DAYS.accepts(JSON.readTree("[1,8]"))).isFalse();
    assertThat(SettingKey.REGISTRATION_CLOSE.accepts(JSON.readTree("{\"weekday\":4}"))).isFalse();
  }
}
