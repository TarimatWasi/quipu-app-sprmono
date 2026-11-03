package com.tarimatwasi.quipu.environment.infrastructure.adapter.out.occupancy;

import com.tarimatwasi.quipu.environment.application.port.out.EnvironmentOccupancyPort;
import com.tarimatwasi.quipu.shared.occupancy.ContractOccupancyQuery;
import com.tarimatwasi.quipu.shared.occupancy.Occupancy;
import com.tarimatwasi.quipu.shared.occupancy.StayOccupancyQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The environment module asks the contracts and the stays, through the shared queries, who holds an
 * environment: a contract in force, or a stay of a temporary guest today or later (RN-12, RN-20,
 * TAR-237).
 */
@Component
public class ContractEnvironmentOccupancyAdapter implements EnvironmentOccupancyPort {

  private final ContractOccupancyQuery contracts;
  private final StayOccupancyQuery stays;
  private final Clock clock;

  ContractEnvironmentOccupancyAdapter(
      ContractOccupancyQuery contracts, StayOccupancyQuery stays, Clock clock) {
    this.contracts = contracts;
    this.stays = stays;
    this.clock = clock;
  }

  @Override
  public boolean isOccupied(Long environmentId) {
    return contracts.environmentOccupied(environmentId)
        || stays.environmentOccupiedFrom(environmentId, LocalDate.now(clock));
  }

  @Override
  public Optional<Occupancy> occupantOf(Long environmentId) {
    LocalDate today = LocalDate.now(clock);
    return contracts
        .contractCrossing(environmentId, today, today)
        .or(() -> stays.stayCrossing(environmentId, today, null));
  }

  @Override
  public List<Occupancy> occupanciesOf(Collection<Long> environmentIds) {
    if (environmentIds.isEmpty()) {
      return List.of();
    }
    List<Occupancy> all = new ArrayList<>(contracts.occupanciesOf(environmentIds));
    all.addAll(stays.occupanciesOf(environmentIds));
    return all;
  }
}
