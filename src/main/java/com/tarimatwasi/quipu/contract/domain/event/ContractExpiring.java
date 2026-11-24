package com.tarimatwasi.quipu.contract.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.time.LocalDate;

/** A contract in force ends within the days of RN-07; stated once per contract and end date. */
public record ContractExpiring(
    EventMetadata metadata, Long contractId, Long guestId, Long environmentId, LocalDate endDate)
    implements DomainEvent {

  @Override
  public String subject() {
    return "contract/" + contractId;
  }
}
