package com.tarimatwasi.quipu.shared.events;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Container of repeated {@link PublishEvent}: one method may state several facts. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PublishEvents {

  /** The facts, published in declaration order. */
  PublishEvent[] value();
}
