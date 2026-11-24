package com.tarimatwasi.quipu.shared.events.delivery;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A listener of domain events kept by the outbox
 * (spring.modulith.events.registry-trigger-annotation): after the commit, in a transaction of its
 * own, on {@link #EXECUTOR}, retried until it completes. Every such listener is idempotent by event
 * id, since the outbox delivers at least once.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Async(DurableListener.EXECUTOR)
@Transactional(propagation = Propagation.REQUIRES_NEW)
@TransactionalEventListener
public @interface DurableListener {

  /** The executor of the durable deliveries. */
  String EXECUTOR = "domainEventExecutor";
}
