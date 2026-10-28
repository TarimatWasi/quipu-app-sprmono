package com.tarimatwasi.quipu.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.domain.ContractTerms;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.TestIds;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** TAR-184 (RN-25, D-27): the debt is of the guest, over every contract it ever had. */
@ExtendWith(MockitoExtension.class)
class GuestDebtServiceTest {

  private static final Long GUEST_ID = TestIds.next();
  private static final Long OLD = TestIds.next();
  private static final Long CURRENT = TestIds.next();

  // 2026-10-14 09:00 in Lima
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T14:00:00Z"));

  @Mock PaymentRepositoryPort payments;
  @Mock PaymentContractPort contracts;

  private GuestDebtService service;

  @BeforeEach
  void setUp() {
    service = new GuestDebtService(payments, contracts, clock);
  }

  private static ContractInfo contract(Long id, String start, @Nullable String end) {
    return new ContractInfo(
        id,
        TestIds.next(),
        new ContractTerms(
            LocalDate.parse(start), end == null ? null : LocalDate.parse(end), null, 5, 3),
        BigDecimal.TEN);
  }

  private static Payment approved(Long contractId, String period) {
    return new Payment(
        TestIds.next(),
        GUEST_ID,
        contractId,
        null,
        YearMonth.parse(period),
        BigDecimal.TEN,
        null,
        PaymentStatus.APPROVED,
        null,
        Instant.parse("2026-10-01T15:00:00Z"),
        TestIds.next());
  }

  @Test
  void aGuestWithoutContractsOwesNothingAndThePaymentsAreNotRead() {
    when(contracts.contractsOf(GUEST_ID)).thenReturn(List.of());

    assertThat(service.owedPeriods(GUEST_ID)).isEmpty();
    verifyNoInteractions(payments);
  }

  @Test
  void theDebtOfAnExpiredContractCountsAndThePaymentsAreReadOnce() {
    when(contracts.contractsOf(GUEST_ID))
        .thenReturn(
            List.of(
                contract(OLD, "2026-05-01", "2026-07-31"), contract(CURRENT, "2026-08-01", null)));
    when(payments.findApprovedOfContracts(GUEST_ID))
        .thenReturn(
            List.of(
                approved(OLD, "2026-05"),
                approved(OLD, "2026-07"),
                approved(CURRENT, "2026-08"),
                approved(CURRENT, "2026-10")));

    assertThat(service.owedPeriods(GUEST_ID))
        .containsExactly(YearMonth.of(2026, 6), YearMonth.of(2026, 9));
    verify(payments).findApprovedOfContracts(GUEST_ID);
  }

  @Test
  void aPaymentOfAnotherContractDoesNotClearTheSameMonthHere() {
    // a renewal that charges October too: October paid on the old one is still owed on the new one
    when(contracts.contractsOf(GUEST_ID))
        .thenReturn(
            List.of(
                contract(OLD, "2026-10-01", "2026-10-31"), contract(CURRENT, "2026-10-01", null)));
    when(payments.findApprovedOfContracts(GUEST_ID)).thenReturn(List.of(approved(OLD, "2026-10")));

    assertThat(service.owedPeriods(GUEST_ID)).containsExactly(YearMonth.of(2026, 10));
  }

  @Test
  void aMonthOwedOnTwoContractsIsListedOnce() {
    when(contracts.contractsOf(GUEST_ID))
        .thenReturn(
            List.of(
                contract(OLD, "2026-09-01", "2026-09-30"), contract(CURRENT, "2026-09-01", null)));
    when(payments.findApprovedOfContracts(GUEST_ID)).thenReturn(List.of());

    assertThat(service.owedPeriods(GUEST_ID))
        .containsExactly(YearMonth.of(2026, 9), YearMonth.of(2026, 10));
  }

  @Test
  void theDayIsTheOneOfLima() {
    // 2026-10-06 03:00 UTC is still the 5th in Lima: October is due today, not yet owed
    service =
        new GuestDebtService(
            payments, contracts, new MutableClock(Instant.parse("2026-10-06T03:00:00Z")));
    when(contracts.contractsOf(GUEST_ID))
        .thenReturn(List.of(contract(CURRENT, "2026-10-01", null)));
    when(payments.findApprovedOfContracts(GUEST_ID)).thenReturn(List.of());

    assertThat(service.owedPeriods(GUEST_ID)).isEmpty();
  }
}
