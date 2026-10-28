package com.tarimatwasi.quipu.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.QuipuApplication;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guard against silent rules: each scope that the skeleton already has must be selected by the rule
 * that targets it, so a typo in a selector cannot make the rule pass on nothing.
 */
class ArchitectureScopesTest {

  private static final List<JavaClass> PRODUCTION =
      List.copyOf(
          new ClassFileImporter()
              .withImportOption(new ImportOption.DoNotIncludeTests())
              .importPackages(ArchitectureRules.ROOT));

  private static List<String> names(java.util.function.Predicate<JavaClass> selector) {
    return PRODUCTION.stream().filter(selector).map(JavaClass::getName).toList();
  }

  @Test
  void arq01_selectsTheApplicationClass() {
    // BE_SPR_ARQ_01 uses areMetaAnnotatedWith(SpringBootConfiguration): the application class
    // carries @SpringBootApplication, which is meta-annotated.
    assertThat(
            names(
                c -> c.isMetaAnnotatedWith(org.springframework.boot.SpringBootConfiguration.class)))
        .containsExactly(QuipuApplication.class.getName());
  }

  @Test
  void skeletonScopes_areNotEmpty() {
    assertThat(names(c -> c.getPackageName().startsWith(ArchitectureRules.ROOT + ".shared")))
        .as("shared package")
        .isNotEmpty();
    assertThat(
            names(
                c -> c.isAnnotatedWith(org.springframework.context.annotation.Configuration.class)))
        .as("@Configuration classes")
        .isNotEmpty();
    assertThat(
            names(
                c ->
                    c.isAssignableTo(org.springframework.web.filter.OncePerRequestFilter.class)
                        && !c.isEquivalentTo(
                            org.springframework.web.filter.OncePerRequestFilter.class)))
        .as("filters")
        .isNotEmpty();
  }

  @Test
  void hexagonalModules_areTheBusinessModules() {
    // TAR_226_HEXAGONAL_PACKAGES selects its modules by name: each one must exist.
    assertThat(ArchitectureRules.HEXAGONAL_MODULES)
        .containsExactlyInAnyOrder(
            "auth", "contract", "environment", "expense", "fine", "guest", "payment")
        .allSatisfy(
            m ->
                assertThat(
                        names(
                            c ->
                                c.getPackageName()
                                    .startsWith(ArchitectureRules.ROOT + "." + m + ".")))
                    .as("classes of " + m)
                    .isNotEmpty());
  }

  @Test
  void moduleScopes_areNotEmpty() {
    // The imported modules (auth, bff) give every structural rule something to select.
    assertThat(names(c -> c.getPackageName().contains(".domain"))).as("domain").isNotEmpty();
    assertThat(names(c -> c.getPackageName().contains(".application.service")))
        .as("application.service")
        .isNotEmpty();
    assertThat(names(c -> c.getPackageName().contains(".application.port.in")))
        .as("application.port.in")
        .isNotEmpty();
    assertThat(names(c -> c.getPackageName().contains(".application.port.out")))
        .as("application.port.out")
        .isNotEmpty();
    assertThat(names(c -> c.getPackageName().contains(".infrastructure.adapter.in")))
        .as("infrastructure input adapters")
        .isNotEmpty();
    assertThat(names(c -> c.getPackageName().contains(".infrastructure.adapter.out")))
        .as("infrastructure output adapters")
        .isNotEmpty();
    assertThat(names(c -> c.getPackageName().contains(".infrastructure.config")))
        .as("infrastructure config")
        .isNotEmpty();
    assertThat(names(c -> c.getPackageName().contains(".adapter.in.rest")))
        .as("rest adapters")
        .isNotEmpty();
    assertThat(names(c -> c.getPackageName().contains(".adapter.out.persistence")))
        .as("persistence adapters")
        .isNotEmpty();
    assertThat(
            names(
                c ->
                    c.isAnnotatedWith(
                        org.springframework.web.bind.annotation.RestController.class)))
        .as("controllers")
        .isNotEmpty();
    assertThat(names(c -> c.isAnnotatedWith(jakarta.persistence.Entity.class)))
        .as("entities")
        .isNotEmpty();
  }
}
