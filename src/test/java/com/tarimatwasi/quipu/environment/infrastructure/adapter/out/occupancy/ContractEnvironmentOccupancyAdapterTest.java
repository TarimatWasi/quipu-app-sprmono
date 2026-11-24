package com.tarimatwasi.quipu.environment.infrastructure.adapter.out.occupancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.shared.occupancy.ContractOccupancyQuery;
import com.tarimatwasi.quipu.shared.occupancy.Occupancy;
import com.tarimatwasi.quipu.shared.occupancy.StayOccupancyQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContractEnvironmentOccupancyAdapterTest {

  private final ContractOccupancyQuery contracts = mock(ContractOccupancyQuery.class);
  private final StayOccupancyQuery stays = mock(StayOccupancyQuery.class);
  private final ContractEnvironmentOccupancyAdapter adapter =
      new ContractEnvironmentOccupancyAdapter(contracts, stays, Clock.systemUTC());

  /** Whoever read the label of the environment: its contracts and its stays alike, once each. */
  @Test
  void theReadersOfTheLabelAreTheGuestsOfItsContractsAndStays() {
    when(contracts.guestsOfEnvironment(7L)).thenReturn(Set.of(5L));
    when(stays.occupanciesOf(List.of(7L)))
        .thenReturn(
            List.of(
                new Occupancy(7L, Occupancy.Kind.STAY, 9L, null, 3L, LocalDate.EPOCH, null),
                new Occupancy(7L, Occupancy.Kind.STAY, 5L, null, 4L, LocalDate.EPOCH, null)));

    assertThat(adapter.labelReadersOf(7L)).containsExactlyInAnyOrder(5L, 9L);
  }
}
