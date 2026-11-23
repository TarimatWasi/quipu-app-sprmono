package com.tarimatwasi.quipu.contract.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** An administrator finished a contract in force by hand (RN-21): the environment is free. */
public record ContractTerminated(
    EventMetadata metadata, Long contractId, Long guestId, Long environmentId)
    implements DomainEvent {

  @Override
  public String subject() {
    return "contract/" + contractId;
  }
}
