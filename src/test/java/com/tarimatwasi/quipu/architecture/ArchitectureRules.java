package com.tarimatwasi.quipu.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.properties.HasOwner;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.repository.Repository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * Production-code rules of the "Guía Spring" and of "Perfil técnico de Quipu" verified with
 * ArchUnit. The ID of each rule is in the field name. Rules that need a custom condition are in
 * {@link ArchitectureConditions}.
 */
final class ArchitectureRules {

  static final String ROOT = "com.tarimatwasi.quipu";

  private ArchitectureRules() {}

  // --- Arquitectura (ARQ) ---

  @ArchTest
  static final ArchRule BE_SPR_ARQ_01 =
      classes()
          .that()
          .areMetaAnnotatedWith(
              SpringBootConfiguration.class) // @SpringBootApplication is a meta-annotation
          .should()
          .resideInAPackage(ROOT);

  @ArchTest
  static final ArchRule BE_SPR_ARQ_02 =
      classes()
          .that()
          .resideInAPackage(ROOT + "..")
          .and()
          .resideOutsideOfPackages(ROOT, ROOT + ".shared..")
          .and()
          .doNotHaveSimpleName("package-info")
          .should()
          .resideInAnyPackage(
              "..domain..",
              "..application..",
              "..port.in..",
              "..port.out..",
              "..adapter.in..",
              "..adapter.out..",
              "..config..");

  @ArchTest static final ArchRule BE_SPR_ARQ_03 = ArchitectureConditions.layersFollowTheTable(ROOT);

  @ArchTest
  static final ArchRule BE_SPR_ARQ_08 =
      classes()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage("java..", "org.jspecify..", "..domain..");

  @ArchTest
  static final ArchRule BE_SPR_ARQ_09_CONTROLLERS =
      classes()
          .that()
          .areAnnotatedWith(RestController.class)
          .should()
          .haveSimpleNameEndingWith("Controller");

  @ArchTest
  static final ArchRule BE_SPR_ARQ_09_USE_CASES =
      classes()
          .that()
          .resideInAPackage("..port.in..")
          .and()
          .areInterfaces()
          .and()
          .doNotHaveSimpleName("package-info")
          .should()
          .haveSimpleNameEndingWith("UseCase");

  @ArchTest
  static final ArchRule BE_SPR_ARQ_09_OUT_PORTS =
      classes()
          .that()
          .resideInAPackage("..port.out..")
          .and()
          .areInterfaces()
          .and()
          .doNotHaveSimpleName("package-info")
          .should()
          .haveSimpleNameEndingWith("Port");

  @ArchTest
  static final ArchRule BE_SPR_ARQ_09_ENTITIES =
      classes()
          .that()
          .areAnnotatedWith(jakarta.persistence.Entity.class)
          .should()
          .haveSimpleNameEndingWith("JpaEntity");

  @ArchTest
  static final ArchRule BE_SPR_ARQ_09_REPOSITORIES =
      classes()
          .that()
          .areAssignableTo(Repository.class)
          .should()
          .haveSimpleNameEndingWith("JpaRepository");

  @ArchTest
  static final ArchRule BE_SPR_ARQ_09_PROPERTIES =
      classes()
          .that()
          .areAnnotatedWith(ConfigurationProperties.class)
          .should()
          .haveSimpleNameEndingWith("Properties")
          // empty until PR3 adds the @ConfigurationProperties records
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule BE_SPR_ARQ_09_EXCEPTIONS =
      classes()
          .that()
          .areAssignableTo(Throwable.class)
          .should()
          .haveSimpleNameEndingWith("Exception");

  // --- Inyección (DI) ---

  @ArchTest static final ArchRule BE_SPR_DI_02 = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

  @ArchTest
  static final ArchRule BE_SPR_DI_01 =
      fields()
          .that()
          .areDeclaredInClassesThat()
          .areAnnotatedWith(Service.class)
          .or()
          .areDeclaredInClassesThat()
          .areAnnotatedWith(Component.class)
          .or()
          .areDeclaredInClassesThat()
          .areAnnotatedWith(Configuration.class)
          .and()
          .areNotStatic()
          .should()
          .beFinal()
          .andShould()
          .notBeAnnotatedWith(Autowired.class);

  @ArchTest
  static final ArchRule BE_SPR_DI_06 =
      noClasses()
          .should()
          .dependOnClassesThat()
          .areAssignableTo(BeanFactory.class)
          .because(
              "beans are injected, never looked up by hand (ApplicationContext is a BeanFactory)");

  // --- Configuración (CFG) ---

  @ArchTest
  static final ArchRule BE_SPR_CFG_01 =
      ArchitectureConditions.propertiesPrefixIsApp()
          // empty until PR3 adds the @ConfigurationProperties records
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule BE_SPR_CFG_02 =
      classes()
          .that()
          .areAnnotatedWith(ConfigurationProperties.class)
          .should()
          .beRecords()
          .andShould()
          .beAnnotatedWith(Validated.class)
          .andShould()
          .notBeAnnotatedWith(Component.class)
          // empty until PR3 adds the @ConfigurationProperties records
          .allowEmptyShould(true);

  // TAR-62 PR3: remove FreezingArchRule when JwtProperties, R2Properties, ResendProperties and
  // AdminProperties replace the multiple @Value.
  @ArchTest
  static final ArchRule BE_SPR_CFG_03 =
      FreezingArchRule.freeze(ArchitectureConditions.atMostOneValuePerClass());

  @ArchTest static final ArchRule BE_SPR_CFG_04 = ArchitectureConditions.noValueOnFieldsNorSpel();

  // --- Código (COD, NUL) ---

  // TAR-62 PR3: remove FreezingArchRule when JwtTokenProvider uses the injected Clock.
  @ArchTest
  static final ArchRule BE_SPR_COD_03 =
      FreezingArchRule.freeze(
          noClasses()
              .should()
              .callMethod(Instant.class, "now")
              .orShould()
              .callMethod(LocalDate.class, "now")
              .orShould()
              .callMethod(LocalDateTime.class, "now")
              .orShould()
              .callMethod(ZonedDateTime.class, "now")
              .orShould()
              .callMethod(System.class, "currentTimeMillis")
              .because("a Clock is injected instead (the clock zone is fixed by the profile)"));

  @ArchTest static final ArchRule BE_SPR_COD_04 = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

  @ArchTest
  static final ArchRule BE_SPR_COD_04_STACK_TRACE =
      noClasses().should().callMethod(Throwable.class, "printStackTrace");

  @ArchTest static final ArchRule BE_SPR_COD_05 = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

  @ArchTest
  static final ArchRule BE_SPR_NUL_03 =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework.lang..", "javax.annotation..", "com.google.code.findbugs..");

  // --- Web (WEB) ---

  @ArchTest
  static final ArchRule BE_SPR_WEB_01 =
      classes()
          .that()
          .areAnnotatedWith(RestController.class)
          .should()
          .resideInAPackage("..adapter.in.rest..");

  // TAR-62 PR2: remove FreezingArchRule when bff reaches auth only through port.in types.
  @ArchTest
  static final ArchRule BE_SPR_WEB_02 =
      FreezingArchRule.freeze(
          noClasses()
              .that()
              .resideInAPackage("..adapter.in.rest..")
              .should()
              .dependOnClassesThat()
              .areAnnotatedWith(jakarta.persistence.Entity.class)
              .orShould()
              .dependOnClassesThat()
              .resideInAPackage("..domain.."));

  @ArchTest static final ArchRule BE_SPR_WEB_03 = ArchitectureConditions.requestBodyIsValidated();

  // --- Aplicación (APP) ---

  @ArchTest
  static final ArchRule BE_SPR_APP_01 =
      classes()
          .that()
          .implement(
              com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage("..port.in.."))
          .should()
          .resideInAPackage("..application..");

  @ArchTest
  static final ArchRule BE_SPR_APP_02 =
      methods()
          .that()
          .areAnnotatedWith(Transactional.class)
          .should()
          .beDeclaredInClassesThat()
          .resideInAPackage("..application..")
          // empty until the first @Transactional use case
          .allowEmptyShould(true);

  // --- Persistencia (DAT) ---

  @ArchTest
  static final ArchRule BE_SPR_DAT_01 =
      classes()
          .that()
          .areAnnotatedWith(jakarta.persistence.Entity.class)
          .or()
          .areAnnotatedWith(jakarta.persistence.MappedSuperclass.class)
          .or()
          .areAnnotatedWith(jakarta.persistence.Embeddable.class)
          .should()
          .resideInAPackage("..adapter.out.persistence..");

  // TAR-62 PR2: remove FreezingArchRule when the Spring Data repository becomes package-private.
  @ArchTest
  static final ArchRule BE_SPR_DAT_02 =
      FreezingArchRule.freeze(
          classes()
              .that()
              .areAssignableTo(Repository.class)
              .should()
              .bePackagePrivate()
              .andShould()
              .resideInAPackage("..adapter.out.persistence.."));

  @ArchTest static final ArchRule BE_SPR_DAT_07 = ArchitectureConditions.toOneRelationsAreLazy();

  // --- Seguridad, observabilidad y concurrencia ---

  @ArchTest
  static final ArchRule BE_SPR_SEC_10 =
      noClasses().should().dependOnClassesThat().areAssignableTo(RestTemplate.class);

  @ArchTest static final ArchRule BE_SPR_OBS_01 = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

  /**
   * BE-SPR-CON-01 (TAR-126, ADR-F4): no unmanaged threads or executors and no scheduled tasks
   * without an ADR; {@code @Async} and {@code @EnableAsync} only in {@code adapter.out}, on the
   * executor that Spring Boot configures.
   */
  @ArchTest
  static final ArchRule BE_SPR_CON_01 =
      CompositeArchRule.of(
              noClasses()
                  .should()
                  .beAnnotatedWith(EnableScheduling.class)
                  .orShould()
                  .dependOnClassesThat()
                  .areAssignableTo(Executors.class)
                  .orShould()
                  .dependOnClassesThat()
                  .areAssignableTo(ThreadGroup.class)
                  .orShould()
                  .callConstructorWhere(
                      JavaCall.Predicates.target(
                          HasOwner.Predicates.With.owner(
                              JavaClass.Predicates.assignableTo(Thread.class))))
                  .because(
                      "any Thread constructor, whatever its overload, creates an unmanaged thread"))
          .and(
              classes()
                  .that()
                  .areAnnotatedWith(EnableAsync.class)
                  .or()
                  .areAnnotatedWith(Async.class)
                  .should()
                  .resideInAPackage("..adapter.out..")
                  .because("@EnableAsync and a class-level @Async belong to the output adapters")
                  .allowEmptyShould(true))
          .and(
              methods()
                  .that()
                  .areAnnotatedWith(Async.class)
                  .should()
                  .beDeclaredInClassesThat()
                  .resideInAPackage("..adapter.out..")
                  .because("the use case and the domain do not know the work leaves in a thread")
                  .allowEmptyShould(true));

  @ArchTest
  static final ArchRule BE_SPR_CON_03 =
      noClasses()
          .that()
          .resideOutsideOfPackage("..adapter.out..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("software.amazon..", "com.resend..")
          .because("the product profile lists here the external SDKs that it adopts");

  // --- Reglas propias de Quipu ---

  @ArchTest
  static final ArchRule QP_SPRMONO_API_02 = ArchitectureConditions.routesUseTheSurfacePrefix();

  @ArchTest
  static final ArchRule QP_SPRMONO_API_03 = ArchitectureConditions.newApiVersionsCoexistWithV1();

  @ArchTest
  static final ArchRule QP_SPRMONO_DAT_01 =
      classes()
          .that()
          .areAnnotatedWith(jakarta.persistence.Entity.class)
          .should()
          .beAssignableTo(ROOT + ".shared.adapter.out.persistence.AuditableEntity");
}
