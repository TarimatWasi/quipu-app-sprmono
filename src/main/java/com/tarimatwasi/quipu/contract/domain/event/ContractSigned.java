package com.tarimatwasi.quipu.contract.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.math.BigDecimal;
import java.time.LocalDate;

/** An administrator registered a signed contract: the guest occupies the environment from then. */
public record ContractSigned(
    EventMetadata metadata,
    Long contractId,
    Long guestId,
    Long environmentId,
    LocalDate startDate,
    BigDecimal monthlyAmount)
    implements DomainEvent {

  @Override
  public String subject() {
    return "contract/" + contractId;
  }
}
