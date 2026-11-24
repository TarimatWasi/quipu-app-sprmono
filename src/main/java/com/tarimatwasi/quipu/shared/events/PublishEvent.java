package com.tarimatwasi.quipu.shared.events;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Publishes {@link #type()} when the annotated use-case method returns normally, inside its
 * transaction: a rollback discards it, and a commit makes it durable in the outbox together with
 * the business change.
 *
 * <p>Each expression of {@link #args()} fills, in order, a record component after the metadata.
 * They are SpEL over the method parameters by name and {@code #result}, the returned value. Every
 * declaration is parsed and checked when the application starts; a broken one stops it.
 *
 * <pre>{@code
 * @Transactional
 * @PublishEvent(type = FineDeleted.class, args = {"#result.id()", "#result.guestId()"})
 * public FineView delete(Long id) { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(PublishEvents.class)
public @interface PublishEvent {

  /** The record to publish; its first component is the {@link EventMetadata}. */
  Class<? extends DomainEvent> type();

  /** One SpEL expression per component after the metadata, in declaration order. */
  String[] args();

  /**
   * Whether the fact happened, for a use case that may change nothing (an idempotent repetition): a
   * boolean SpEL over the parameters and {@code #result}. Empty means always. It states the fact,
   * never its consequences, which belong to the listeners.
   */
  String when() default "";

  /**
   * A stable key for a fact that may be stated again (a sweep over time, a retried request): SpEL
   * whose text, with the event type, becomes the event id (name-based UUID). Every consumer is
   * idempotent by event id, so the same fact is audited and notified once. Empty means a random id
   * per occurrence.
   */
  String id() default "";
}
