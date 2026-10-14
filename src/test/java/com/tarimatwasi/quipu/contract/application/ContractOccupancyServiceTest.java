package com.tarimatwasi.quipu.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.contract.port.out.ContractRepositoryPort;
import com.tarimatwasi.quipu.support.MutableClock;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractOccupancyServiceTest {

  @Mock ContractRepositoryPort contracts;

  @Test
  void asksTheRepositoryAboutTheDayInLima() {
    // 2026-10-15 03:00 UTC is still 2026-10-14 in Lima
    var service =
        new ContractOccupancyService(
            contracts, new MutableClock(Instant.parse("2026-10-15T03:00:00Z")));
    when(contracts.existsInForce(5L, LocalDate.parse("2026-10-14"))).thenReturn(true);

    assertThat(service.environmentOccupied(5L)).isTrue();
    assertThat(service.environmentOccupied(6L)).isFalse();
  }
}
