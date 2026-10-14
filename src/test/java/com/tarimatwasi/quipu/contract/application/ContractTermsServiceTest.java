package com.tarimatwasi.quipu.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.contract.domain.Contract;
import com.tarimatwasi.quipu.contract.port.out.ContractRepositoryPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractTermsServiceTest {

  @Mock ContractRepositoryPort contracts;
  @InjectMocks ContractTermsService service;

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
  }

  @Test
  void environmentsOf_mapsEachContractToItsEnvironment() {
    when(contracts.findAllByIds(Set.of(1L, 2L)))
        .thenReturn(
            List.of(contract(1, 10, "2025-01-01", null), contract(2, 11, "2026-01-01", null)));

    assertThat(service.environmentsOf(Set.of(1L, 2L))).isEqualTo(Map.of(1L, 10L, 2L, 11L));
  }

  @Test
  void environmentsOf_noContractsDoesNotTouchTheRepository() {
    assertThat(service.environmentsOf(Set.of())).isEmpty();
    verifyNoInteractions(contracts);
  }
}
