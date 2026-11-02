package com.tarimatwasi.quipu.contract.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.contract.application.port.in.ContractNotFoundException;
import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase.ContractState;
import com.tarimatwasi.quipu.contract.application.port.out.ContractFilePort;
import com.tarimatwasi.quipu.contract.application.port.out.ContractRepositoryPort;
import com.tarimatwasi.quipu.contract.domain.Contract;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.TestIds;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
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
    when(contracts.findAllByGuest(GUEST_ID)).thenReturn(List.of(contract(true, "2027-01-01")));
    when(files.signedUrl("contracts/a.pdf")).thenReturn("https://signed");

    var view = service().ownContracts(GUEST_ID).current();

    assertThat(view.status()).isEqualTo(ContractState.TERMINATED);
    assertThat(view.fileUrl()).isEqualTo("https://signed");
  }

  @Test
  void anExpiredContractIsStillShown() {
    when(contracts.findAllByGuest(GUEST_ID)).thenReturn(List.of(contract(false, "2026-10-01")));
    when(files.signedUrl("contracts/a.pdf")).thenReturn("https://signed");

    assertThat(service().ownContracts(GUEST_ID).current().status())
        .isEqualTo(ContractState.EXPIRED);
  }

  @Test
  void aGuestThatNeverHadAContractIsNotFound() {
    when(contracts.findAllByGuest(GUEST_ID)).thenReturn(List.of());

    assertThatThrownBy(() -> service().ownContracts(GUEST_ID))
        .isInstanceOf(ContractNotFoundException.class);
  }

  private static Contract between(long id, String start, String end) {
    return new Contract(
        id,
        8L,
        GUEST_ID,
        LocalDate.parse(start),
        LocalDate.parse(end),
        new BigDecimal("800.00"),
        5,
        3,
        "contracts/" + id + ".pdf",
        "a.pdf",
        10,
        null);
  }

  /** TAR-237: the contract in force today and a renewal already loaded after it. */
  @Test
  void theRunningContractIsCurrentAndALoadedRenewalIsNext() {
    when(contracts.findAllByGuest(GUEST_ID))
        .thenReturn(
            List.of(
                between(1L, "2026-01-01", "2026-12-31"), between(2L, "2027-01-01", "2027-12-31")));

    var own = service().ownContracts(GUEST_ID);

    assertThat(own.current().id()).isEqualTo(1L);
    assertThat(own.next()).isNotNull();
    assertThat(Objects.requireNonNull(own.next()).id()).isEqualTo(2L);
  }

  /** TAR-237 (RN-07): a contract that starts later is in force since it was loaded. */
  @Test
  void aContractThatStartsLaterIsCurrentWhenNoneIsRunning() {
    when(contracts.findAllByGuest(GUEST_ID))
        .thenReturn(List.of(between(3L, "2026-11-01", "2027-10-31")));

    var own = service().ownContracts(GUEST_ID);

    assertThat(own.current().id()).isEqualTo(3L);
    assertThat(own.next()).isNull();
  }
}
