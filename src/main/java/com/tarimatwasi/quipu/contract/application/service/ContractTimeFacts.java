package com.tarimatwasi.quipu.contract.application.service;

import com.tarimatwasi.quipu.contract.domain.event.ContractExpiring;
import com.tarimatwasi.quipu.shared.events.PublishEvent;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The facts time alone makes true about contracts; each method states one, once per id. */
@Service
public class ContractTimeFacts {

  /** The contract ends soon (RN-07); a new end date is a new fact. */
  @Transactional
  @PublishEvent(
      type = ContractExpiring.class,
      id = "#contractId + ':' + #endDate",
      args = {"#contractId", "#guestId", "#environmentId", "#endDate"})
  public void expiring(Long contractId, Long guestId, Long environmentId, LocalDate endDate) {
    // The fact is the whole change.
  }
}
