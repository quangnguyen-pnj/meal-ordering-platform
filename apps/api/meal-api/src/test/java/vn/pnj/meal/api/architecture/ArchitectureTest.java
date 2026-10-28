package vn.pnj.meal.api.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Date;
import org.junit.jupiter.api.Test;
import vn.pnj.meal.api.clock.ClockService;

/**
 * Plan 7.1 rule 3: only {@link ClockService} reads the wall clock. Business code takes {@code now}
 * from it, so fake time drives every deadline. Covers meal-rules and meal-api main code.
 */
class ArchitectureTest {
  private static final JavaClasses MAIN =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("vn.pnj.meal");

  @Test
  void onlyClockServiceReadsTheWallClock() {
    ArchRule rule =
        noClasses()
            .that()
            .doNotHaveFullyQualifiedName(ClockService.class.getName())
            .should()
            .callMethod(Instant.class, "now")
            .orShould()
            .callMethod(LocalDate.class, "now")
            .orShould()
            .callMethod(LocalDateTime.class, "now")
            .orShould()
            .callMethod(LocalTime.class, "now")
            .orShould()
            .callMethod(ZonedDateTime.class, "now")
            .orShould()
            .callMethod(OffsetDateTime.class, "now")
            .orShould()
            .callMethod(System.class, "currentTimeMillis")
            .orShould()
            .callMethod(Clock.class, "systemUTC")
            .orShould()
            .callMethod(Clock.class, "systemDefaultZone")
            .orShould()
            .callConstructor(Date.class)
            .because("plan 7.1 rule 3: take now from ClockService so fake time works");
    rule.check(MAIN);
  }

  @Test
  void rulesStayFreeOfSpringAndTheDatabase() {
    noClasses()
        .that()
        .resideInAPackage("vn.pnj.meal.rules..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "jakarta..", "java.sql..", "vn.pnj.meal.api..")
        .because("business rules are pure functions (AGENTS.md rule 2)")
        .check(MAIN);
  }
}
