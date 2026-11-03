package com.tarimatwasi.quipu.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.payment.application.port.in.AdminDashboardUseCase.ChargeView;
import com.tarimatwasi.quipu.payment.application.port.in.AdminDashboardUseCase.DashboardView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.GuestKind;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentState;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.QueueItemView;
import com.tarimatwasi.quipu.payment.application.port.in.ManageRefundsUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.ManageRefundsUseCase.PendingCreditView;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase.ChargeState;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.GuestContract;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentEnvironmentPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort.GuestProfile;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort.NotActivated;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort.PendingGuest;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentReviewerPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort.ChargedStay;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort.StayRef;
import com.tarimatwasi.quipu.payment.domain.ContractTerms;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.MutableClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * HU-A04, TAR-236: one charge per active contract (its period of the current month) and per stay in
 * force, counted as the account status reads them, and the blocks of «Lo que toca ahora». The day
 * is 2026-10-14 in Lima.
 */
@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {

  private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T14:00:00Z"));

  @Mock PaymentRepositoryPort payments;
  @Mock PaymentContractPort contracts;
  @Mock PaymentStayPort stays;
  @Mock PaymentGuestPort guests;
  @Mock PaymentEnvironmentPort environments;
  @Mock PaymentReviewerPort reviewers;
  @Mock ManagePaymentsUseCase queue;
  @Mock ManageRefundsUseCase refunds;

  private AdminDashboardService service() {
    return new AdminDashboardService(
        payments,
        contracts,
        new PaymentDirectory(guests, environments, stays, reviewers),
        queue,
        refunds,
        clock);
  }

  @BeforeEach
  void everyGuestAndEnvironmentIsKnown() {
    lenient()
        .when(guests.profilesOf(anyCollection()))
        .thenAnswer(
            call -> {
              Map<Long, GuestProfile> found = new HashMap<>();
              for (Long id : call.<Collection<Long>>getArgument(0)) {
                found.put(
                    id,
                    new GuestProfile(
                        id, "Huésped " + id, "1000000" + id, id < 50, true, false, "DNI"));
              }
              return found;
            });
    lenient()
        .when(environments.codesOf(anyCollection()))
        .thenAnswer(
            call -> {
              Map<Long, String> found = new HashMap<>();
              for (Long id : call.<Collection<Long>>getArgument(0)) {
                found.put(id, "E" + id);
              }
              return found;
            });
    lenient().when(queue.list(any(), any())).thenReturn(new CursorPage<>(List.of(), null));
    lenient().when(guests.notActivated()).thenReturn(new NotActivated(0, 0, null));
  }

  /** A contract of the guest {@code id} in the environment {@code 100 + id}. */
  private static GuestContract contract(
      long id, String start, @Nullable String end, int paymentDay, int graceDays) {
    return new GuestContract(
        id,
        new ContractInfo(
            id,
            100L + id,
            new ContractTerms(
                LocalDate.parse(start),
                end == null ? null : LocalDate.parse(end),
                null,
                paymentDay,
                graceDays),
            new BigDecimal("800.00")));
  }

  /** A stay of the guest {@code 50 + id}, without environment. */
  private static ChargedStay stay(long id, String start, String end) {
    return new ChargedStay(
        50L + id,
        new StayRef(
            id, LocalDate.parse(start), LocalDate.parse(end), new BigDecimal("10.00"), null));
  }

  private static Payment ofPeriod(
      long id, long contractId, YearMonth period, PaymentStatus status, @Nullable String verified) {
    return new Payment(
        id,
        contractId,
        contractId,
        null,
        period,
        BigDecimal.TEN,
        verified == null ? null : new BigDecimal(verified),
        status,
        status == PaymentStatus.REJECTED ? "No coincide" : null,
        status == PaymentStatus.PENDING
            ? null
            : Instant.parse("2026-10-0" + (id % 9 + 1) + "T00:00:00Z"),
        null,
        Instant.EPOCH);
  }

  private static Payment ofStay(long id, long stayId, PaymentStatus status) {
    return new Payment(
        id,
        50L + stayId,
        null,
        stayId,
        null,
        BigDecimal.TEN,
        status == PaymentStatus.APPROVED ? BigDecimal.TEN : null,
        status,
        null,
        null,
        null,
        Instant.EPOCH);
  }

  private static PendingCreditView credit(Long guestId, String amount) {
    return new PendingCreditView(
        guestId,
        null,
        GuestKind.TEMPORARY,
        null,
        new BigDecimal(amount),
        1L,
        null,
        null,
        null,
        null);
  }

  @Test
  void eachActiveContractCountsTheStateOfItsPeriodOfTheMonth() {
    // Due on the 5th with 3 days of grace: overdue on the 14th without a live payment.
    var overdue = contract(1, "2026-10-01", null, 5, 3);
    // Due on the 12th with 5 days of grace: still within the grace on the 14th.
    var inGrace = contract(2, "2026-10-01", null, 12, 5);
    var underReview = contract(3, "2026-10-01", null, 5, 3);
    var paid = contract(4, "2026-10-01", null, 5, 3);
    var rejectedOnly = contract(5, "2026-10-01", null, 5, 3);
    when(contracts.allContracts())
        .thenReturn(List.of(overdue, inGrace, underReview, paid, rejectedOnly));
    when(payments.findAllByContracts(List.of(1L, 2L, 3L, 4L, 5L)))
        .thenReturn(
            List.of(
                ofPeriod(31, 3, OCTOBER, PaymentStatus.PENDING, null),
                ofPeriod(42, 4, OCTOBER, PaymentStatus.APPROVED, "800"),
                ofPeriod(51, 5, OCTOBER, PaymentStatus.REJECTED, null)));

    DashboardView view = service().dashboard();

    assertThat(view.paymentsUpToDate()).isEqualTo(1);
    assertThat(view.paymentsUnderReview()).isEqualTo(1);
    assertThat(view.paymentsPending()).isEqualTo(1);
    // A rejected voucher does not close the period (RN-05): the fifth one is overdue too.
    assertThat(view.paymentsOverdue()).isEqualTo(2);
    ChargeView rejected = view.charges().get(4);
    assertThat(rejected.status()).isEqualTo(ChargeState.OVERDUE);
    assertThat(rejected.paymentId()).isEqualTo(51L);
    assertThat(rejected.paymentRejected()).isTrue();
    assertThat(view.charges().get(2).paymentRejected()).isFalse();
  }

  @Test
  void aChargeSaysWhoOwesItWhereAndWhatIsLeftAfterTheCredit() {
    when(contracts.allContracts()).thenReturn(List.of(contract(1, "2026-09-01", null, 5, 3)));
    when(payments.findAllByContracts(List.of(1L)))
        .thenReturn(List.of(ofPeriod(11, 1, YearMonth.of(2026, 9), PaymentStatus.APPROVED, "850")));

    ChargeView charge = service().dashboard().charges().getFirst();

    assertThat(charge.guestId()).isEqualTo(1L);
    assertThat(charge.guestName()).isEqualTo("Huésped 1");
    assertThat(charge.documentType()).isEqualTo("DNI");
    assertThat(charge.guestType()).isEqualTo(GuestKind.CONTRACT);
    assertThat(charge.environmentCode()).isEqualTo("E101");
    assertThat(charge.period()).isEqualTo(OCTOBER);
    assertThat(charge.dueDate()).isEqualTo(LocalDate.parse("2026-10-05"));
    // RN-38: September left 50, so October owes 750
    assertThat(charge.amount()).isEqualByComparingTo("750");
    assertThat(charge.paymentId()).isNull();
  }

  @Test
  void aContractThatStartsInALaterMonthDoesNotCount() {
    when(contracts.allContracts())
        .thenReturn(
            List.of(contract(1, "2026-11-01", null, 5, 3), contract(2, "2026-10-01", null, 20, 0)));

    DashboardView view = service().dashboard();

    assertThat(
            List.of(
                view.paymentsUpToDate(),
                view.paymentsUnderReview(),
                view.paymentsPending(),
                view.paymentsOverdue()))
        .containsExactly(0, 0, 1, 0);
    assertThat(view.charges()).hasSize(1);
  }

  @Test
  void eachStayInForceCountsTheStateOfItsPayment() {
    var unpaid = stay(7, "2026-10-01", "2026-10-20");
    var underReview = stay(8, "2026-10-10", "2026-10-14");
    var paid = stay(9, "2026-10-14", "2026-10-30");
    var coming = stay(10, "2026-10-20", "2026-10-30");
    when(stays.allStays()).thenReturn(List.of(unpaid, underReview, paid, coming));
    when(payments.findAllByStays(List.of(7L, 8L, 9L, 10L)))
        .thenReturn(
            List.of(
                ofStay(71, 7, PaymentStatus.REJECTED),
                ofStay(81, 8, PaymentStatus.PENDING),
                ofStay(91, 9, PaymentStatus.APPROVED)));

    DashboardView view = service().dashboard();

    assertThat(
            List.of(
                view.paymentsUpToDate(),
                view.paymentsUnderReview(),
                view.paymentsPending(),
                view.paymentsOverdue()))
        .containsExactly(1, 1, 1, 0);
    assertThat(view.charges()).extracting(ChargeView::stayId).containsExactly(7L, 8L, 9L);
    assertThat(view.charges().getFirst().guestType()).isEqualTo(GuestKind.TEMPORARY);
    assertThat(view.charges().getFirst().environmentCode()).isNull();
    assertThat(view.charges().getFirst().dueDate()).isEqualTo(LocalDate.parse("2026-10-20"));
  }

  /** RN-25: every overdue period of any contract and every overdue stay, the oldest first. */
  @Test
  void theOverdueChargesAreEveryOneOfAnyMonthTheOldestFirst() {
    when(contracts.allContracts()).thenReturn(List.of(contract(1, "2026-07-01", null, 5, 3)));
    when(stays.allStays()).thenReturn(List.of(stay(7, "2026-09-01", "2026-09-10")));

    DashboardView view = service().dashboard();

    assertThat(view.overdue().count()).isEqualTo(5);
    assertThat(view.overdue().total()).isEqualByComparingTo("3210.00");
    assertThat(view.overdue().first())
        .extracting(ChargeView::dueDate)
        .containsExactly(
            LocalDate.parse("2026-07-05"),
            LocalDate.parse("2026-08-05"),
            LocalDate.parse("2026-09-05"));
    // The ended stay is overdue but not a charge of today
    assertThat(view.charges()).hasSize(1);
  }

  @Test
  void thePendingVouchersAreThePageOfTheQueueOfTheOldestTwentyWithTheirTotal() {
    QueueItemView row = org.mockito.Mockito.mock(QueueItemView.class);
    List<QueueItemView> oldest = java.util.Collections.nCopies(20, row);
    when(queue.list(PaymentState.PENDING, new PageQuery(null, 20)))
        .thenReturn(new CursorPage<>(oldest, "next"));
    when(payments.countByStatus(PaymentStatus.PENDING)).thenReturn(23L);

    DashboardView view = service().dashboard();

    assertThat(view.pendingVouchers()).hasSize(20);
    assertThat(view.pendingVouchersTotal()).isEqualTo(23L);
  }

  @Test
  void theRejectedTheRefundsTheExpiringContractsAndTheGuestsNotActivated() {
    when(payments.findByStatus(PaymentStatus.REJECTED))
        .thenReturn(
            List.of(
                ofPeriod(25, 2, YearMonth.of(2026, 9), PaymentStatus.REJECTED, null),
                ofPeriod(21, 2, OCTOBER, PaymentStatus.REJECTED, null)));
    when(refunds.pendingCredits()).thenReturn(List.of(credit(60L, "30.00"), credit(61L, "20.50")));
    when(contracts.allContracts())
        .thenReturn(
            List.of(
                contract(1, "2026-01-01", "2026-12-31", 5, 3),
                contract(2, "2026-01-01", "2026-11-10", 5, 3),
                contract(3, "2026-01-01", "2026-10-31", 5, 3)));
    when(guests.notActivated())
        .thenReturn(
            new NotActivated(
                2,
                1,
                new PendingGuest(9L, "DNI", "70000001", Instant.parse("2026-10-01T00:00:00Z"))));

    DashboardView view = service().dashboard();

    assertThat(view.rejected().count()).isEqualTo(2);
    // The one rejected first: 21 on 2026-10-04, 25 on 2026-10-08
    var oldest = Objects.requireNonNull(view.rejected().oldest());
    assertThat(oldest.paymentId()).isEqualTo(21L);
    assertThat(oldest.period()).isEqualTo(OCTOBER);
    assertThat(view.refunds().count()).isEqualTo(2);
    assertThat(view.refunds().total()).isEqualByComparingTo("50.50");
    assertThat(view.expiringContracts().count()).isEqualTo(2);
    var expiring = Objects.requireNonNull(view.expiringContracts().first());
    assertThat(expiring.contractId()).isEqualTo(3L);
    assertThat(expiring.environmentCode()).isEqualTo("E103");
    assertThat(view.guestsNotActivated().expired()).isEqualTo(1);
    assertThat(Objects.requireNonNull(view.guestsNotActivated().first()).guestId()).isEqualTo(9L);
  }

  @Test
  void thePaymentsAreReadOnceForAllContractsAndOnceForAllStays() {
    when(contracts.allContracts())
        .thenReturn(
            List.of(contract(1, "2026-10-01", null, 5, 3), contract(2, "2026-10-01", null, 5, 3)));
    when(stays.allStays())
        .thenReturn(
            List.of(stay(7, "2026-10-01", "2026-10-20"), stay(8, "2026-10-01", "2026-10-20")));

    service().dashboard();

    verify(payments, times(1)).findAllByContracts(anyCollection());
    verify(payments, times(1)).findAllByStays(anyCollection());
    verify(payments, never()).findAllByContract(any());
    verify(payments, never()).findAllByStay(any());
  }

  @Test
  void nothingToCountIsAllZerosWithoutReadingAnyPaymentOfAContractOrStay() {
    DashboardView view = service().dashboard();

    assertThat(view.charges()).isEmpty();
    assertThat(view.overdue().count()).isZero();
    assertThat(view.overdue().total()).isEqualByComparingTo("0");
    assertThat(view.rejected().oldest()).isNull();
    assertThat(view.refunds().total()).isEqualByComparingTo("0");
    assertThat(view.expiringContracts().first()).isNull();
    assertThat(view.guestsNotActivated().count()).isZero();
    verify(payments, never()).findAllByContracts(anyCollection());
    verify(payments, never()).findAllByStays(anyCollection());
  }
}
