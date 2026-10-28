package vn.pnj.meal.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseUrlTest {

  @Test
  void convertsThePrismaStyleUrlOfEnvExample() {
    DatabaseUrl url =
        DatabaseUrl.parse("postgresql://meal:meal_dev_password@localhost:5433/meal?schema=public");
    assertThat(url.jdbcUrl())
        .isEqualTo("jdbc:postgresql://localhost:5433/meal?currentSchema=public");
    assertThat(url.username()).isEqualTo("meal");
    assertThat(url.password()).isEqualTo("meal_dev_password");
  }

  @Test
  void decodesCredentialsAndKeepsOtherParameters() {
    DatabaseUrl url = DatabaseUrl.parse("postgres://u%40x:p%3Aw%2Fd@db/meal?sslmode=require");
    assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://db/meal?sslmode=require");
    assertThat(url.username()).isEqualTo("u@x");
    assertThat(url.password()).isEqualTo("p:w/d");
  }

  @Test
  void neverPrintsThePassword() {
    assertThat(DatabaseUrl.parse("postgresql://u:secret@db/meal").toString())
        .doesNotContain("secret");
  }

  @Test
  void rejectsOtherSchemesAndMissingDatabase() {
    assertThatThrownBy(() -> DatabaseUrl.parse("mysql://u:p@db/meal"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DatabaseUrl.parse("postgresql://u:p@db"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
