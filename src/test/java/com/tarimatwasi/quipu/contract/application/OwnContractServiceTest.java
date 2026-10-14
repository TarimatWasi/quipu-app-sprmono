package com.tarimatwasi.quipu.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.contract.domain.Contract;
import com.tarimatwasi.quipu.contract.port.in.ContractNotFoundException;
import com.tarimatwasi.quipu.contract.port.in.ManageContractsUseCase.ContractState;
import com.tarimatwasi.quipu.contract.port.out.ContractFilePort;
import com.tarimatwasi.quipu.contract.port.out.ContractRepositoryPort;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.TestIds;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** CU-18, RN-26: a guest reads its own contract, in any state. */
@ExtendWith(MockitoExtension.class)
class OwnContractServiceTest {

  private static final Long GUEST_ID = TestIds.next();

  @Mock ContractRepositoryPort contracts;
  @Mock ContractFilePort files;

  private OwnContractService service() {
    return new OwnContractService(
        contracts, files, new MutableClock(Instant.parse("2026-10-14T14:00:00Z")));
  }

  private static final java.time.Instant TERMINATED_AT =
      java.time.Instant.parse("2026-09-30T20:00:00Z");

  private static Contract contract(boolean terminated, String end) {
    return new Contract(
        7L,
        8L,
        GUEST_ID,
        LocalDate.parse("2026-01-01"),
        LocalDate.parse(end),
        new BigDecimal("800.00"),
        5,
        3,
        "contracts/a.pdf",
        "a.pdf",
        10,
        terminated ? TERMINATED_AT : null);
  }

  @Test
  void aTerminatedContractIsStillShown() {
    when(contracts.findLatestByGuest(GUEST_ID))
        .thenReturn(Optional.of(contract(true, "2027-01-01")));
    when(files.signedUrl("contracts/a.pdf")).thenReturn("https://signed");

    var view = service().ownContract(GUEST_ID);

    assertThat(view.status()).isEqualTo(ContractState.TERMINATED);
    assertThat(view.fileUrl()).isEqualTo("https://signed");
  }

  @Test
  void anExpiredContractIsStillShown() {
    when(contracts.findLatestByGuest(GUEST_ID))
        .thenReturn(Optional.of(contract(false, "2026-10-01")));
    when(files.signedUrl("contracts/a.pdf")).thenReturn("https://signed");

    assertThat(service().ownContract(GUEST_ID).status()).isEqualTo(ContractState.EXPIRED);
  }

  @Test
  void aGuestThatNeverHadAContractIsNotFound() {
    when(contracts.findLatestByGuest(GUEST_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().ownContract(GUEST_ID))
        .isInstanceOf(ContractNotFoundException.class);
  }
}
