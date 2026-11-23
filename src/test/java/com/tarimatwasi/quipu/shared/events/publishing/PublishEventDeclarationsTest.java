package com.tarimatwasi.quipu.shared.events.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import com.tarimatwasi.quipu.shared.events.PublishEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterNameDiscoverer;

class PublishEventDeclarationsTest {
  private final PublishEventDeclarations declarations = new PublishEventDeclarations();

  record Paid(EventMetadata metadata, Long paymentId, Long guestId) implements DomainEvent {
    @Override
    public String subject() {
      return "payment/" + paymentId;
    }
  }

  record Receipt(Long id, Long guestId) {}

  record Outcome(Long id, Long guestId, boolean happened) {}

  static class Valid {
    @PublishEvent(
        type = Paid.class,
        args = {"#id", "#result.guestId()"})
    public Receipt pay(Long id) {
      return new Receipt(id, 7L);
    }

    @PublishEvent(
        type = Paid.class,
        args = {"#id", "#guest"})
    @PublishEvent(
        type = Paid.class,
        args = {"#id + 1", "#guest"})
    public void twice(Long id, long guest) {}

    @PublishEvent(
        type = Paid.class,
        when = "#result.happened()",
        args = {"#result.id()", "#result.guestId()"})
    public Outcome maybe(Long id, boolean happen) {
      return new Outcome(id, 2L, happen);
    }

    @PublishEvent(
        type = Paid.class,
        id = "'due:' + #id",
        args = {"#id", "2L"})
    public void stable(Long id) {}
  }

  /** A class, not a record: nothing to build it from. */
  static final class NotARecord implements DomainEvent {
    @Override
    public EventMetadata metadata() {
      return new EventMetadata(UUID.randomUUID(), Instant.EPOCH, null);
    }

    @Override
    public String subject() {
      return "other/1";
    }
  }

  record MetadataLast(Long id, EventMetadata metadata) implements DomainEvent {
    @Override
    public String subject() {
      return "other/" + id;
    }
  }

  record Positive(EventMetadata metadata, Long id) implements DomainEvent {
    Positive {
      if (id < 0) {
        throw new IllegalArgumentException("negative");
      }
    }

    @Override
    public String subject() {
      return "other/" + id;
    }
  }

  static class Picky {
    @PublishEvent(
        type = Positive.class,
        args = {"#id"})
    public void publish(Long id) {}

    @PublishEvent(
        type = Positive.class,
        args = {"#result"})
    public Long fromTheResult(Long id) {
      return id;
    }
  }

  static class Broken {
    @PublishEvent(
        type = NotARecord.class,
        args = {})
    public void notARecord(Long id) {}

    @PublishEvent(
        type = MetadataLast.class,
        args = {"#id"})
    public void metadataLast(Long id) {}

    @PublishEvent(
        type = Paid.class,
        when = "#id ==",
        args = {"#id", "2L"})
    public void brokenCondition(Long id) {}

    @PublishEvent(
        type = Paid.class,
        args = {"#id", "#id.equals()"})
    public void accessorWithArguments(Long id) {}

    @PublishEvent(
        type = Paid.class,
        args = {"#paymentId", "2L"})
    public Long unknownVariable(Long id) {
      return id;
    }

    @PublishEvent(
        type = Paid.class,
        args = {"#id"})
    public Long wrongArity(Long id) {
      return id;
    }

    @PublishEvent(
        type = Paid.class,
        args = {"#id +", "2L"})
    public Long syntax(Long id) {
      return id;
    }

    @PublishEvent(
        type = Paid.class,
        args = {"#id", "#result"})
    public void resultOnVoid(Long id) {}

    @PublishEvent(
        type = Paid.class,
        args = {"#id", "#name"})
    public void wrongType(Long id, String name) {}

    @PublishEvent(
        type = Paid.class,
        args = {"#id", "#result.owner()"})
    public Receipt missingAccessor(Long id) {
      return new Receipt(id, 1L);
    }

    @PublishEvent(
        type = Paid.class,
        when = "#result.id()",
        args = {"#id", "2L"})
    public Receipt conditionNotBoolean(Long id) {
      return new Receipt(id, 1L);
    }
  }

  @Test
  void validDeclarations_compile() {
    assertThatCode(() -> declarations.validate(Valid.class)).doesNotThrowAnyException();
  }

  @Test
  void everyBrokenDeclaration_isReportedAtOnce() {
    assertThatThrownBy(() -> declarations.validate(Broken.class))
        .isInstanceOf(InvalidPublishEventException.class)
        .hasMessageContaining("Broken.unknownVariable: unknown variable '#paymentId'")
        .hasMessageContaining("Broken.wrongArity: Paid needs 2 expressions, found 1")
        .hasMessageContaining("Broken.syntax: cannot parse '#id +'")
        .hasMessageContaining("Broken.resultOnVoid: '#result' on a void method")
        .hasMessageContaining("Broken.wrongType: '#name' is String but Paid.guestId is Long")
        .hasMessageContaining("Broken.missingAccessor: Receipt has no owner()")
        .hasMessageContaining(
            "Broken.conditionNotBoolean: when '#result.id()' is Long, not boolean")
        .hasMessageContaining(
            "Broken.notARecord: NotARecord must be a record whose first component is EventMetadata")
        .hasMessageContaining(
            "Broken.metadataLast: MetadataLast must be a record whose first component is"
                + " EventMetadata")
        .hasMessageContaining("Broken.brokenCondition: cannot parse when '#id =='")
        .hasMessageContaining("Broken.accessorWithArguments: Long has no equals()");
  }

  /** An event whose own rule refuses the facts is a failure of the method, never a lost fact. */
  @Test
  void anEventThatRefusesItsFacts_failsTheBuild() throws Exception {
    Method publish = Picky.class.getMethod("publish", Long.class);
    var metadata = new EventMetadata(UUID.randomUUID(), Instant.EPOCH, null);

    assertThatThrownBy(
            () -> declarations.build(publish, new Picky(), new Object[] {-1L}, null, metadata))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Cannot build Positive");
  }

  /**
   * A class compiled without parameter names gives none, or only some: its expressions can still
   * read the result, and an unknown name is reported, never a failure of the discovery.
   */
  @Test
  void withoutParameterNames_onlyTheResultIsKnown() {
    String[] partial = {null};
    var withoutNames =
        new PublishEventDeclarations(
            new ParameterNameDiscoverer() {
              @Override
              public String @Nullable [] getParameterNames(Method method) {
                return method.getName().equals("publish") ? null : partial;
              }

              @Override
              public String @Nullable [] getParameterNames(Constructor<?> constructor) {
                return null;
              }
            });

    assertThatThrownBy(() -> withoutNames.validate(Picky.class))
        .isInstanceOf(InvalidPublishEventException.class)
        .hasMessageContaining("Picky.publish: unknown variable '#id'")
        .hasMessageNotContaining("fromTheResult");
  }

  @Test
  void build_fillsTheComponentsFromParametersAndResult() throws Exception {
    Method pay = Valid.class.getMethod("pay", Long.class);
    var metadata = new EventMetadata(UUID.randomUUID(), Instant.EPOCH, 3L);

    var events =
        declarations.build(pay, new Valid(), new Object[] {5L}, new Receipt(5L, 7L), metadata);

    assertThat(events).containsExactly(new Paid(metadata, 5L, 7L));
  }

  @Test
  void repeatedDeclarations_publishInOrderAndConvertPrimitives() throws Exception {
    Method twice = Valid.class.getMethod("twice", Long.class, long.class);
    var metadata = new EventMetadata(UUID.randomUUID(), Instant.EPOCH, null);

    var events = declarations.build(twice, new Valid(), new Object[] {5L, 9L}, null, metadata);

    assertThat(events).containsExactly(new Paid(metadata, 5L, 9L), new Paid(metadata, 6L, 9L));
  }

  @Test
  void when_publishesOnlyTheFactsThatHappened() throws Exception {
    Method maybe = Valid.class.getMethod("maybe", Long.class, boolean.class);
    var metadata = new EventMetadata(UUID.randomUUID(), Instant.EPOCH, null);

    assertThat(
            declarations.build(
                maybe, new Valid(), new Object[] {5L, true}, new Outcome(5L, 2L, true), metadata))
        .containsExactly(new Paid(metadata, 5L, 2L));
    assertThat(
            declarations.build(
                maybe, new Valid(), new Object[] {5L, false}, new Outcome(5L, 2L, false), metadata))
        .isEmpty();
  }

  @Test
  void id_isTheSameForTheSameFactAndDiffersForAnother() throws Exception {
    Method stable = Valid.class.getMethod("stable", Long.class);
    var first = new EventMetadata(UUID.randomUUID(), Instant.EPOCH, null);
    var second = new EventMetadata(UUID.randomUUID(), Instant.EPOCH, null);

    var once = declarations.build(stable, new Valid(), new Object[] {5L}, null, first);
    var again = declarations.build(stable, new Valid(), new Object[] {5L}, null, second);
    var other = declarations.build(stable, new Valid(), new Object[] {6L}, null, first);

    assertThat(once.getFirst().metadata().eventId())
        .isEqualTo(again.getFirst().metadata().eventId())
        .isNotEqualTo(other.getFirst().metadata().eventId())
        .isNotEqualTo(first.eventId());
  }
}
