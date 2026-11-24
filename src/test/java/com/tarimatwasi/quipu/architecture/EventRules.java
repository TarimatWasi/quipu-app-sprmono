package com.tarimatwasi.quipu.architecture;

import static com.tngtech.archunit.lang.SimpleConditionEvent.violated;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import com.tarimatwasi.quipu.shared.events.PublishEvent;
import com.tarimatwasi.quipu.shared.events.PublishEvents;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * The domain event platform (shared events). Events are stored in the outbox and the audit trail,
 * so their shape is a contract and their content must never carry personal data.
 */
final class EventRules {

  /** What a fact may carry: identifiers, enumerations, dates and amounts; never free text. */
  private static final Set<Class<?>> FACT_TYPES =
      Set.of(
          Long.class,
          long.class,
          Integer.class,
          int.class,
          Boolean.class,
          boolean.class,
          UUID.class,
          BigDecimal.class,
          Instant.class,
          LocalDate.class,
          YearMonth.class);

  private EventRules() {}

  /** QP-EVT-01: the event kernel is plain Java, so the domain of every module may use it. */
  @ArchTest
  static final ArchRule QP_EVT_01 =
      classes()
          .that()
          .resideInAPackage(ArchitectureRules.ROOT + ".shared.events")
          .and()
          .doNotHaveSimpleName("package-info")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage(
              "java..", "org.jspecify..", ArchitectureRules.ROOT + ".shared.events");

  /**
   * QP-EVT-02: an event is a record in {@code <module>.domain.event}, its first component is the
   * metadata and the others are facts (Ley 29733: no personal data in the outbox or the trail).
   */
  @ArchTest
  static final ArchRule QP_EVT_02 =
      classes()
          .that()
          .implement(DomainEvent.class)
          .should()
          .resideInAPackage("..domain.event..")
          .andShould(beRecordsOfFacts());

  /**
   * QP-EVT-03: only public, transactional use-case methods publish: the proxy intercepts only
   * those, and the event joins their transaction.
   */
  @ArchTest
  static final ArchRule QP_EVT_03 =
      methods()
          .that()
          .areAnnotatedWith(PublishEvent.class)
          .or()
          .areAnnotatedWith(PublishEvents.class)
          .should()
          .beDeclaredInClassesThat()
          .resideInAPackage("..application.service..")
          .andShould()
          .bePublic()
          .andShould(beTransactional())
          .allowEmptyShould(true);

  /** QP-EVT-04: facts are published declaratively, never by hand from a service or an adapter. */
  @ArchTest
  static final ArchRule QP_EVT_04 =
      noClasses()
          .that()
          .resideOutsideOfPackage(ArchitectureRules.ROOT + ".shared.events.publishing")
          .should()
          .callMethodWhere(JavaCall.Predicates.target(HasName.Predicates.name("publishEvent")));

  /**
   * QP-TX-01: work that depends on how a transaction ends goes through AfterTransaction, which
   * never removes an object when the outcome is unknown (a removal on "not committed" lost the file
   * of a row that had committed). The live invalidations keep their own buffer per transaction.
   */
  @ArchTest
  static final ArchRule QP_TX_01 =
      noClasses()
          .that()
          .resideOutsideOfPackage(ArchitectureRules.ROOT + ".shared.transaction")
          .and()
          .doNotHaveSimpleName("ChangeStreamRegistry")
          .should()
          .callMethodWhere(
              JavaCall.Predicates.target(HasName.Predicates.name("registerSynchronization")));

  /** QP-EVT-06: only the work a domain event starts acts as the system, never a request. */
  @ArchTest
  static final ArchRule QP_EVT_06 =
      noClasses()
          .that()
          .resideOutsideOfPackages(
              "..adapter.in.event..", ArchitectureRules.ROOT + ".shared.access")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName(ArchitectureRules.ROOT + ".shared.access.SystemActor");

  private static ArchCondition<JavaClass> beRecordsOfFacts() {
    return new ArchCondition<>("be records of facts after their EventMetadata") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        Class<?> type = item.reflect();
        RecordComponent[] components = type.getRecordComponents();
        if (components == null) {
          events.add(violated(item, item.getName() + " is not a record"));
          return;
        }
        if (components.length == 0 || components[0].getType() != EventMetadata.class) {
          events.add(violated(item, item.getName() + " does not start with its EventMetadata"));
        }
        for (int i = 1; i < components.length; i++) {
          if (!isFact(components[i].getGenericType())) {
            events.add(
                violated(
                    item,
                    item.getName()
                        + "."
                        + components[i].getName()
                        + " is "
                        + components[i].getGenericType().getTypeName()
                        + ", not an identifier, enumeration, date or amount"));
          }
        }
      }
    };
  }

  private static boolean isFact(Type type) {
    if (type instanceof Class<?> plain) {
      return FACT_TYPES.contains(plain) || plain.isEnum();
    }
    if (type instanceof ParameterizedType generic
        && generic.getRawType() instanceof Class<?> raw
        && Collection.class.isAssignableFrom(raw)) {
      return isFact(generic.getActualTypeArguments()[0]);
    }
    return false;
  }

  private static ArchCondition<JavaMethod> beTransactional() {
    return new ArchCondition<>("be @Transactional (on the method or its class)") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        if (!method.isAnnotatedWith(Transactional.class)
            && !method.getOwner().isAnnotatedWith(Transactional.class)) {
          events.add(violated(method, method.getFullName() + " publishes outside a transaction"));
        }
      }
    };
  }
}
