package com.tarimatwasi.quipu.environment.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** An administrator edited the code or the type of an environment. */
public record EnvironmentUpdated(EventMetadata metadata, Long environmentId)
    implements DomainEvent {

  @Override
  public String subject() {
    return "environment/" + environmentId;
  }
}
