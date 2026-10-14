package com.tarimatwasi.quipu.environment.adapter.out.occupancy;

import com.tarimatwasi.quipu.environment.port.out.EnvironmentOccupancyPort;
import com.tarimatwasi.quipu.shared.occupancy.ContractOccupancyQuery;
import org.springframework.stereotype.Component;

/**
 * The environment module asks the contracts, through the shared query, who holds an environment.
 */
@Component
public class ContractEnvironmentOccupancyAdapter implements EnvironmentOccupancyPort {

  private final ContractOccupancyQuery contracts;

  ContractEnvironmentOccupancyAdapter(ContractOccupancyQuery contracts) {
    this.contracts = contracts;
  }

  @Override
  public boolean isOccupied(Long environmentId) {
    return contracts.environmentOccupied(environmentId);
  }
}
