package com.tarimatwasi.quipu.environment.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** An administrator took an environment out of the operational listings (RN-12). */
public record EnvironmentDeactivated(EventMetadata metadata, Long environmentId)
    implements DomainEvent {

  @Override
  public String subject() {
    return "environment/" + environmentId;
  }
}
