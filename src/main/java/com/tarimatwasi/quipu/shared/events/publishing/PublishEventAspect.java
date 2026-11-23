package com.tarimatwasi.quipu.shared.events.publishing;

import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.time.Clock;
import java.util.UUID;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes the events of a {@code @PublishEvent} method after it returns normally. It runs inside
 * the transaction ({@link EventOrdering}): the Modulith outbox row is then written by the business
 * transaction itself. Outside a transaction the publication would be written on its own and its
 * listeners would never run (spike, 2026-11-22), so that case fails loudly instead.
 */
@Aspect
@Component
public class PublishEventAspect implements Ordered {
  private final ApplicationEventPublisher publisher;
  private final PublishEventDeclarations declarations;
  private final Clock clock;

  public PublishEventAspect(
      ApplicationEventPublisher publisher, PublishEventDeclarations declarations, Clock clock) {
    this.publisher = publisher;
    this.declarations = declarations;
    this.clock = clock;
  }

  @AfterReturning(
      pointcut =
          "@annotation(com.tarimatwasi.quipu.shared.events.PublishEvent)"
              + " || @annotation(com.tarimatwasi.quipu.shared.events.PublishEvents)",
      returning = "result")
  public void publish(JoinPoint joinPoint, @Nullable Object result) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new EventOutsideTransactionException(joinPoint.getSignature().toShortString());
    }
    var target = joinPoint.getTarget();
    var method =
        AopUtils.getMostSpecificMethod(
            ((MethodSignature) joinPoint.getSignature()).getMethod(),
            AopUtils.getTargetClass(target));
    var metadata = new EventMetadata(UUID.randomUUID(), clock.instant(), actor());
    for (var event : declarations.build(method, target, joinPoint.getArgs(), result, metadata)) {
      publisher.publishEvent(event);
    }
  }

  /** The account id of the access token (its subject), or null for the system. */
  private static @Nullable Long actor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return null;
    }
    String name = authentication.getName();
    return !name.isEmpty() && name.chars().allMatch(Character::isDigit) ? Long.valueOf(name) : null;
  }

  @Override
  public int getOrder() {
    return EventOrdering.PUBLISH;
  }
}
