package com.tarimatwasi.quipu.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.contract.domain.Contract;
import com.tarimatwasi.quipu.contract.port.out.ContractRepositoryPort;
import com.tarimatwasi.quipu.support.MutableClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractTermsServiceTest {

  @Mock ContractRepositoryPort contracts;

  // 2026-10-15 03:00 UTC is still 2026-10-14 in Lima.
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T03:00:00Z"));
  private ContractTermsService service;

  @BeforeEach
  void setUp() {
    service = new ContractTermsService(contracts, clock);
  }

  private static Contract contract(
      long id, long environment, String start, @Nullable Instant terminatedAt) {
    return new Contract(
        id,
        environment,
        9L,
        LocalDate.parse(start),
        null,
        new BigDecimal("800.00"),
        5,
        3,
        "contracts/a.pdf",
        "a.pdf",
        10,
        terminatedAt);
  }

  @Test
  void termsOfGuest_carryTheTermsAndTheInstantOfTheTermination() {
    Instant finished = Instant.parse("2026-09-20T15:00:00Z");
    when(contracts.findAllByGuest(9L))
        .thenReturn(
            List.of(contract(1, 10, "2025-01-01", finished), contract(2, 11, "2026-01-01", null)));

    var terms = service.termsOfGuest(9L);

    assertThat(terms).extracting(t -> t.contractId()).containsExactly(1L, 2L);
    assertThat(terms.get(0).terminatedAt()).isEqualTo(finished);
    assertThat(terms.get(1).terminatedAt()).isNull();
    assertThat(terms.get(1).environmentId()).isEqualTo(11L);
    assertThat(terms.get(1).paymentDay()).isEqualTo(5);
    assertThat(terms.get(1).graceDays()).isEqualTo(3);
    assertThat(terms.get(1).monthlyAmount()).isEqualByComparingTo("800.00");
  }

  @Test
  void lockedTermsOfGuest_readTheTermsFromTheLockedRows() {
    Instant finished = Instant.parse("2026-09-20T15:00:00Z");
    when(contracts.findAllByGuestForUpdate(9L))
        .thenReturn(List.of(contract(1, 10, "2025-01-01", finished)));

    var terms = service.lockedTermsOfGuest(9L);

    assertThat(terms).extracting(t -> t.contractId()).containsExactly(1L);
    assertThat(terms.get(0).terminatedAt()).isEqualTo(finished);
    org.mockito.Mockito.verify(contracts, org.mockito.Mockito.never()).findAllByGuest(9L);
  }

  @Test
  void environmentsOf_mapsEachContractToItsEnvironment() {
    when(contracts.findAllByIds(Set.of(1L, 2L)))
        .thenReturn(
            List.of(contract(1, 10, "2025-01-01", null), contract(2, 11, "2026-01-01", null)));

    assertThat(service.environmentsOf(Set.of(1L, 2L))).isEqualTo(Map.of(1L, 10L, 2L, 11L));
  }

  @Test
  void currentTermsOfEnvironment_readTheContractsInForceOnTheDayOfLima() {
    when(contracts.findAllInForce(10L, LocalDate.of(2026, 10, 14)))
        .thenReturn(List.of(contract(2, 10, "2026-07-01", null)));

    var terms = service.currentTermsOfEnvironment(10L);

    assertThat(terms).extracting(t -> t.contractId()).containsExactly(2L);
    assertThat(terms.get(0).environmentId()).isEqualTo(10L);
    assertThat(terms.get(0).startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
  }

  @Test
  void currentTermsOfEnvironment_aFreeEnvironmentHasNone() {
    when(contracts.findAllInForce(10L, LocalDate.of(2026, 10, 14))).thenReturn(List.of());

    assertThat(service.currentTermsOfEnvironment(10L)).isEmpty();
  }

  @Test
  void environmentsOf_noContractsDoesNotTouchTheRepository() {
    assertThat(service.environmentsOf(Set.of())).isEmpty();
    verifyNoInteractions(contracts);
  }
}
