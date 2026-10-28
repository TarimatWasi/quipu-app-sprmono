package com.tarimatwasi.quipu.contract.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.contract.application.port.out.ContractRepositoryPort;
import com.tarimatwasi.quipu.contract.domain.Contract;
import com.tarimatwasi.quipu.support.MutableClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
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

  @Test
  void aGuestHasAContractInForceOnlyWhileOneIsActiveTodayInLima() {
    var service =
        new ContractOccupancyService(
            contracts, new MutableClock(Instant.parse("2026-10-15T03:00:00Z")));
    when(contracts.findAllByGuest(7L))
        .thenReturn(
            List.of(
                contract(1L, "2026-10-13", null),
                contract(2L, null, Instant.parse("2026-09-01T00:00:00Z")),
                contract(3L, "2026-10-14", null)));
    when(contracts.findAllByGuest(8L)).thenReturn(List.of(contract(4L, "2026-10-13", null)));

    assertThat(service.guestContractInForce(7L)).contains(3L);
    assertThat(service.guestContractInForce(8L)).isEmpty();
  }

  private static Contract contract(Long id, @Nullable String end, @Nullable Instant terminated) {
    return new Contract(
        id,
        5L,
        7L,
        LocalDate.parse("2026-01-01"),
        end == null ? null : LocalDate.parse(end),
        BigDecimal.TEN,
        5,
        3,
        "contracts/a.pdf",
        "a.pdf",
        10,
        terminated);
  }
}
