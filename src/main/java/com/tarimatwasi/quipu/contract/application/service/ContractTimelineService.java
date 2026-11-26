package com.tarimatwasi.quipu.contract.application.service;

import com.tarimatwasi.quipu.contract.application.port.in.ContractTimelineUseCase;
import com.tarimatwasi.quipu.contract.application.port.out.ContractRepositoryPort;
import com.tarimatwasi.quipu.contract.domain.Contract;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runs on the outbox delivery of the business day, without a user (RN-07 of the domain). */
@Service
public class ContractTimelineService implements ContractTimelineUseCase {
  private final ContractRepositoryPort contracts;
  private final ContractTimeFacts facts;

  ContractTimelineService(ContractRepositoryPort contracts, ContractTimeFacts facts) {
    this.contracts = contracts;
    this.facts = facts;
  }

  @Override
  @Transactional
  public void sweep(LocalDate day) {
    for (Contract contract : contracts.findAllInForce(day)) {
      if (contract.expiringSoon(day)) {
        facts.expiring(
            contract.id(),
            contract.guestId(),
            contract.environmentId(),
            Objects.requireNonNull(contract.endDate()));
      }
    }
  }
}
