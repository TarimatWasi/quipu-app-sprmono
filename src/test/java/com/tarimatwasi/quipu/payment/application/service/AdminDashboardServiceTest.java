package com.tarimatwasi.quipu.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.payment.application.port.in.AdminDashboardUseCase.DashboardView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentView;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort.StoredPayment;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.payment.domain.ContractTerms;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.StayTerms;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.MutableClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.LongStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * HU-A04: one state per active contract (its period of the current month) and per stay in course,
 * counted as the account status reads them. The day is 2026-10-14 in Lima.
 */
@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {

  private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
  private static final LocalDate TODAY = LocalDate.parse("2026-10-14");

  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T14:00:00Z"));

  @Mock PaymentRepositoryPort payments;
  @Mock PaymentContractPort contracts;
  @Mock PaymentStayPort stays;

  private AdminDashboardService service() {
    return new AdminDashboardService(payments, contracts, stays, clock);
  }

  private static ContractInfo contract(long id, String start, int paymentDay, int graceDays) {
    return new ContractInfo(
        id,
        100L + id,
        new ContractTerms(LocalDate.parse(start), null, null, paymentDay, graceDays),
        new BigDecimal("800.00"));
  }

  private static Payment ofPeriod(long id, long contractId, PaymentStatus status) {
    return new Payment(
        id, 1L, contractId, null, OCTOBER, BigDecimal.TEN, null, status, null, null, null);
  }

  private static Payment ofStay(long id, long stayId, PaymentStatus status) {
    return new Payment(id, 2L, null, stayId, null, BigDecimal.TEN, null, status, null, null, null);
  }

  private static StoredPayment stored(long id) {
    return new StoredPayment(
        new Payment(
            id, 3L, null, 9L, null, BigDecimal.TEN, null, PaymentStatus.PENDING, null, null, null),
        "vouchers/" + id,
        VoucherType.PDF,
        Instant.parse("2026-10-01T00:00:00Z").plusSeconds(id));
  }

  private void noPendingVouchers() {
    when(payments.findQueue(any(), any())).thenReturn(new CursorPage<>(List.of(), null));
    when(payments.countByStatus(PaymentStatus.PENDING)).thenReturn(0L);
  }

  private void noStays() {
    when(stays.staysInForceOn(TODAY)).thenReturn(List.of());
  }

  private void noContracts() {
    when(contracts.contractsInForce()).thenReturn(List.of());
  }

  @Test
  void eachActiveContractCountsTheStateOfItsPeriodOfTheMonth() {
    // Due on the 5th with 3 days of grace: overdue on the 14th without a live payment.
    ContractInfo overdue = contract(1, "2026-01-01", 5, 3);
    // Due on the 12th with 5 days of grace: still within the grace on the 14th.
    ContractInfo inGrace = contract(2, "2026-01-01", 12, 5);
    ContractInfo underReview = contract(3, "2026-01-01", 5, 3);
    ContractInfo paid = contract(4, "2026-01-01", 5, 3);
    ContractInfo rejectedOnly = contract(5, "2026-01-01", 5, 3);
    when(contracts.contractsInForce())
        .thenReturn(List.of(overdue, inGrace, underReview, paid, rejectedOnly));
    when(payments.findAllByContractsAndPeriod(List.of(1L, 2L, 3L, 4L, 5L), OCTOBER))
        .thenReturn(
            List.of(
                ofPeriod(31, 3, PaymentStatus.PENDING),
                ofPeriod(41, 4, PaymentStatus.REJECTED),
                ofPeriod(42, 4, PaymentStatus.APPROVED),
                ofPeriod(51, 5, PaymentStatus.REJECTED)));
    noStays();
    noPendingVouchers();

    DashboardView view = service().dashboard();

    assertThat(view.paymentsUpToDate()).isEqualTo(1);
    assertThat(view.paymentsUnderReview()).isEqualTo(1);
    assertThat(view.paymentsPending()).isEqualTo(1);
    // A rejected voucher does not close the period (RN-05): the fifth one is overdue too.
    assertThat(view.paymentsOverdue()).isEqualTo(2);
  }

  @Test
  void aContractThatStartsInALaterMonthDoesNotCount() {
    when(contracts.contractsInForce())
        .thenReturn(List.of(contract(1, "2026-11-01", 5, 3), contract(2, "2026-01-01", 20, 0)));
    when(payments.findAllByContractsAndPeriod(List.of(1L, 2L), OCTOBER)).thenReturn(List.of());
    noStays();
    noPendingVouchers();

    DashboardView view = service().dashboard();

    assertThat(
            List.of(
                view.paymentsUpToDate(),
                view.paymentsUnderReview(),
                view.paymentsPending(),
                view.paymentsOverdue()))
        .containsExactly(0, 0, 1, 0);
  }

  @Test
  void eachStayInCourseCountsTheStateOfItsPayment() {
    noContracts();
    StayTerms unpaid = stay(7, "2026-10-01", "2026-10-20");
    StayTerms underReview = stay(8, "2026-10-10", "2026-10-14");
    StayTerms paid = stay(9, "2026-10-14", "2026-10-30");
    when(stays.staysInForceOn(TODAY)).thenReturn(List.of(unpaid, underReview, paid));
    when(payments.findAllByStays(List.of(7L, 8L, 9L)))
        .thenReturn(
            List.of(
                ofStay(71, 7, PaymentStatus.REJECTED),
                ofStay(81, 8, PaymentStatus.PENDING),
                ofStay(91, 9, PaymentStatus.APPROVED)));
    noPendingVouchers();

    DashboardView view = service().dashboard();

    assertThat(
            List.of(
                view.paymentsUpToDate(),
                view.paymentsUnderReview(),
                view.paymentsPending(),
                view.paymentsOverdue()))
        .containsExactly(1, 1, 1, 0);
  }

  private static StayTerms stay(long id, String start, String end) {
    return new StayTerms(id, LocalDate.parse(start), LocalDate.parse(end), BigDecimal.TEN);
  }

  @Test
  void thePaymentsAreReadOnceForAllContractsAndOnceForAllStays() {
    when(contracts.contractsInForce())
        .thenReturn(List.of(contract(1, "2026-01-01", 5, 3), contract(2, "2026-01-01", 5, 3)));
    when(payments.findAllByContractsAndPeriod(anyCollection(), any())).thenReturn(List.of());
    when(stays.staysInForceOn(TODAY))
        .thenReturn(
            List.of(stay(7, "2026-10-01", "2026-10-20"), stay(8, "2026-10-01", "2026-10-20")));
    when(payments.findAllByStays(anyCollection())).thenReturn(List.of());
    noPendingVouchers();

    service().dashboard();

    verify(payments, times(1)).findAllByContractsAndPeriod(anyCollection(), any());
    verify(payments, times(1)).findAllByStays(anyCollection());
    verify(payments, never()).findAllByContract(any());
    verify(payments, never()).findAllByStay(any());
  }

  @Test
  void thePendingVouchersAreThePageOfTheQueueOfTheOldestTwentyWithTheirTotal() {
    noContracts();
    noStays();
    List<StoredPayment> oldest = LongStream.rangeClosed(1, 20).mapToObj(id -> stored(id)).toList();
    when(payments.findQueue(any(), any())).thenReturn(new CursorPage<>(oldest, "next"));
    when(payments.countByStatus(PaymentStatus.PENDING)).thenReturn(23L);

    DashboardView view = service().dashboard();

    ArgumentCaptor<@Nullable PaymentStatus> status = ArgumentCaptor.captor();
    ArgumentCaptor<PageQuery> page = ArgumentCaptor.captor();
    verify(payments).findQueue(status.capture(), page.capture());
    assertThat(status.getValue()).isEqualTo(PaymentStatus.PENDING);
    assertThat(page.getValue()).isEqualTo(new PageQuery(null, 20));
    assertThat(view.pendingVouchers())
        .extracting(PaymentView::id)
        .containsExactlyElementsOf(LongStream.rangeClosed(1, 20).boxed().toList());
    assertThat(view.pendingVouchersTotal()).isEqualTo(23L);
  }

  @Test
  void nothingToCountIsAllZerosWithoutReadingAnyPayment() {
    noContracts();
    noStays();
    noPendingVouchers();

    assertThat(service().dashboard()).isEqualTo(new DashboardView(0, 0, 0, 0, List.of(), 0L));
    verify(payments, never()).findAllByContractsAndPeriod(anyCollection(), any());
    verify(payments, never()).findAllByStays(anyCollection());
  }
}
