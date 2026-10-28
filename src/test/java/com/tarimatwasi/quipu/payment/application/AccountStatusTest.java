package com.tarimatwasi.quipu.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.environment.application.port.in.EnvironmentNotFoundException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.payment.domain.ContractTerms;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.port.in.OwnPaymentsUseCase.ChargeState;
import com.tarimatwasi.quipu.payment.port.in.OwnPaymentsUseCase.PeriodView;
import com.tarimatwasi.quipu.payment.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.port.out.PaymentEnvironmentPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentFilePort;
import com.tarimatwasi.quipu.payment.port.out.PaymentGuestPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentGuestPort.GuestProfile;
import com.tarimatwasi.quipu.payment.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.TestIds;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * TAR-44 (RF-06): the status by period, as the administrator reads it of a guest of either type or
 * of the current contract of an environment through {@link PaymentService}.
 */
@ExtendWith(MockitoExtension.class)
class AccountStatusTest {

  private static final Long ADMIN = TestIds.next();
  private static final Long GUEST = TestIds.next();
  private static final Long CONTRACT = TestIds.next();
  private static final Long ENVIRONMENT = TestIds.next();
  private static final Long PAYMENT = TestIds.next();
  private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
  private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

  // 2026-10-14 09:00 in Lima: October is past its payment day plus the grace days.
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T14:00:00Z"));

  @Mock PaymentRepositoryPort payments;
  @Mock PaymentFilePort files;
  @Mock PaymentGuestPort guests;
  @Mock PaymentContractPort contracts;
  @Mock PaymentEnvironmentPort environments;
  @Mock PaymentStayPort stays;

  private PaymentService service;

  @BeforeEach
  void setUp() {
    service = new PaymentService(payments, files, guests, contracts, environments, stays, clock);
  }

  private static GuestProfile guest(boolean contractType, boolean active) {
    return new GuestProfile(GUEST, "Ana Quispe", "12345678", contractType, active, !active);
  }

  private static ContractInfo contractFromSeptember() {
    return new ContractInfo(
        CONTRACT,
        ENVIRONMENT,
        ContractTerms.of(LocalDate.of(2026, 9, 1), null, null, ZoneId.of("America/Lima"), 5, 3),
        new BigDecimal("800.00"));
  }

  private static Payment approved(YearMonth period) {
    return new Payment(
        PAYMENT,
        GUEST,
        CONTRACT,
        null,
        period,
        new BigDecimal("800.00"),
        "Yape",
        PaymentStatus.APPROVED,
        null,
        Instant.parse("2026-09-03T15:00:00Z"),
        ADMIN);
  }

  @Test
  void anInactiveGuestWithAContractHasEveryChargedPeriodClassified() {
    when(guests.profileOf(GUEST)).thenReturn(guest(true, false));
    when(contracts.contractsOf(GUEST)).thenReturn(List.of(contractFromSeptember()));
    when(payments.findAllByContract(CONTRACT)).thenReturn(List.of(approved(SEPTEMBER)));

    List<PeriodView> periods = service.accountStatus(GUEST);

    assertThat(periods)
        .extracting(PeriodView::period, PeriodView::status, PeriodView::contractId)
        .containsExactly(
            tuple(SEPTEMBER, ChargeState.PAID, CONTRACT),
            tuple(OCTOBER, ChargeState.OVERDUE, CONTRACT));
    assertThat(periods.get(0).paymentId()).isEqualTo(PAYMENT);
    assertThat(periods.get(0).amount()).isEqualByComparingTo("800.00");
    assertThat(periods.get(1).paymentId()).isNull();
    assertThat(periods.get(1).dueDate()).isEqualTo(LocalDate.of(2026, 10, 5));
  }

  @Test
  void aGuestWithoutContractsHasNoPeriods() {
    when(guests.profileOf(GUEST)).thenReturn(guest(false, true));
    when(contracts.contractsOf(GUEST)).thenReturn(List.of());

    assertThat(service.accountStatus(GUEST)).isEmpty();
    verify(payments, never()).findAllByContract(any());
  }

  @Test
  void aGuestThatDoesNotExistIsNotFoundBeforeAnythingIsRead() {
    when(guests.profileOf(GUEST)).thenThrow(new GuestNotFoundException());

    assertThatThrownBy(() -> service.accountStatus(GUEST))
        .isInstanceOf(GuestNotFoundException.class);
    verifyNoInteractions(contracts, payments);
  }

  @Test
  void anEnvironmentHasThePeriodsOfItsCurrentContractOnly() {
    when(contracts.currentContractsOf(ENVIRONMENT)).thenReturn(List.of(contractFromSeptember()));
    when(payments.findAllByContract(CONTRACT)).thenReturn(List.of(approved(SEPTEMBER)));

    List<PeriodView> periods = service.environmentAccountStatus(ENVIRONMENT);

    assertThat(periods)
        .extracting(PeriodView::period, PeriodView::status, PeriodView::contractId)
        .containsExactly(
            tuple(SEPTEMBER, ChargeState.PAID, CONTRACT),
            tuple(OCTOBER, ChargeState.OVERDUE, CONTRACT));
    assertThat(periods.get(0).paymentId()).isEqualTo(PAYMENT);
    verify(environments).requireExists(ENVIRONMENT);
    verify(contracts, never()).contractsOf(any());
  }

  @Test
  void anEnvironmentWithoutACurrentContractHasNoPeriods() {
    when(contracts.currentContractsOf(ENVIRONMENT)).thenReturn(List.of());

    assertThat(service.environmentAccountStatus(ENVIRONMENT)).isEmpty();
    verify(payments, never()).findAllByContract(any());
  }

  @Test
  void anEnvironmentThatDoesNotExistIsNotFoundBeforeAnythingIsRead() {
    doThrow(new EnvironmentNotFoundException()).when(environments).requireExists(ENVIRONMENT);

    assertThatThrownBy(() -> service.environmentAccountStatus(ENVIRONMENT))
        .isInstanceOf(EnvironmentNotFoundException.class);
    verifyNoInteractions(contracts, payments);
  }
}
