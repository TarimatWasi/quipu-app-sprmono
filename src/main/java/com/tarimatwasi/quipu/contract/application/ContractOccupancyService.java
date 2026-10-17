package com.tarimatwasi.quipu.contract.application;

import com.tarimatwasi.quipu.contract.domain.Contract;
import com.tarimatwasi.quipu.contract.port.out.ContractRepositoryPort;
import com.tarimatwasi.quipu.shared.occupancy.ContractOccupancyQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Answers the modules that own environments or guests which of them a contract holds today. */
@Service
public class ContractOccupancyService implements ContractOccupancyQuery {

  private final ContractRepositoryPort contracts;
  private final Clock clock;

  ContractOccupancyService(ContractRepositoryPort contracts, Clock clock) {
    this.contracts = contracts;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean environmentOccupied(Long environmentId) {
    return contracts.existsInForce(environmentId, ContractViews.today(clock));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Long> guestContractInForce(Long guestId) {
    LocalDate today = ContractViews.today(clock);
    return contracts.findAllByGuest(guestId).stream()
        .filter(contract -> contract.occupies(today))
        .map(Contract::id)
        .findFirst();
  }
}
