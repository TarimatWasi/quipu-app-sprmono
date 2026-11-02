package com.tarimatwasi.quipu.contract.application.service;

import com.tarimatwasi.quipu.contract.application.port.out.ContractRepositoryPort;
import com.tarimatwasi.quipu.contract.domain.Contract;
import com.tarimatwasi.quipu.shared.occupancy.ContractOccupancyQuery;
import com.tarimatwasi.quipu.shared.occupancy.Occupancy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
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
  public boolean environmentTaken(Long environmentId, LocalDate start, @Nullable LocalDate end) {
    return contracts.existsCrossing(environmentId, start, end);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Occupancy> occupanciesOf(Collection<Long> environmentIds) {
    return contracts.findAllByEnvironments(environmentIds).stream().map(this::occupancy).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<Occupancy> occupanciesOfGuests(Collection<Long> guestIds) {
    return contracts.findAllByGuests(guestIds).stream().map(this::occupancy).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Set<Long> guestsWithContractInForce(Collection<Long> guestIds) {
    LocalDate today = ContractViews.today(clock);
    return contracts.findAllByGuests(guestIds).stream()
        .filter(contract -> contract.occupies(today))
        .map(Contract::guestId)
        .collect(Collectors.toSet());
  }

  /** A contract finished by hand (RN-21) ends on the day it was finished, in the zone of Quipu. */
  private Occupancy occupancy(Contract contract) {
    Instant terminatedAt = contract.terminatedAt();
    LocalDate end =
        terminatedAt != null
            ? LocalDate.ofInstant(terminatedAt, clock.getZone())
            : contract.endDate();
    return new Occupancy(
        contract.environmentId(),
        Occupancy.Kind.CONTRACT,
        contract.guestId(),
        contract.id(),
        null,
        contract.startDate(),
        end);
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
