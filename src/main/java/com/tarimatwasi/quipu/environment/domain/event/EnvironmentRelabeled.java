package com.tarimatwasi.quipu.environment.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/**
 * The label of an environment (code or type) changed: it also appears in the history of every guest
 * that ever occupied it.
 */
public record EnvironmentRelabeled(EventMetadata metadata, Long environmentId)
    implements DomainEvent {

  @Override
  public String subject() {
    return "environment/" + environmentId;
  }
}
