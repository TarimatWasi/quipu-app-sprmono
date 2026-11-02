package com.tarimatwasi.quipu.payment.application.port.in;

import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.GuestKind;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.QueueItemView;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase.ChargeState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** HU-A04, CU-04, TAR-236: the home of the administrator (A1); only the administrator reads it. */
public interface AdminDashboardUseCase {

  /** The most vouchers awaiting review that the summary lists. */
  int MAX_PENDING_VOUCHERS = 20;

  /** The overdue charges named in «Lo que toca ahora». */
  int MAX_OVERDUE_LISTED = 3;

  /** How many days ahead a contract counts as expiring (RN-07). */
  int EXPIRING_DAYS = 30;

  /**
   * A charge of the home: the period of a contract or a stay, with who owes it.
   *
   * @param amount the monthly amount less the credit applied (RN-38), or the agreed amount
   * @param dueDate the payment day of the period (RN-10), or the check-out date of the stay
   * @param paymentId the live payment when UNDER_REVIEW or PAID, or the rejected one nobody
   *     corrected; null without any
   * @param paymentRejected UNPAID or OVERDUE with a rejected payment not corrected yet (RN-05)
   */
  record ChargeView(
      Long guestId,
      @Nullable String guestName,
      String documentType,
      String documentNumber,
      GuestKind guestType,
      @Nullable String environmentCode,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable YearMonth period,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      BigDecimal amount,
      LocalDate dueDate,
      ChargeState status,
      @Nullable Long paymentId,
      boolean paymentRejected) {}

  /** The overdue charges of this month or earlier (RN-10, RN-25); the oldest first. */
  record OverdueView(long count, BigDecimal total, List<ChargeView> first) {}

  /** The oldest payment rejected that nobody corrected yet. */
  record RejectedPaymentView(
      Long paymentId,
      @Nullable String guestName,
      @Nullable YearMonth period,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      Instant rejectedAt) {}

  /** The payments rejected and not corrected (RN-05). */
  record RejectedView(long count, @Nullable RejectedPaymentView oldest) {}

  /** The credit to refund (RN-38, RN-39): how many guests and how much. */
  record RefundsView(long count, BigDecimal total) {}

  /** The active contract that ends first. */
  record ExpiringContractView(
      Long contractId,
      @Nullable String guestName,
      @Nullable String environmentCode,
      LocalDate endDate) {}

  /** Active contracts that end in {@value #EXPIRING_DAYS} days or less (RN-07). */
  record ExpiringView(long count, @Nullable ExpiringContractView first) {}

  /** A guest pending activation. */
  record PendingGuestView(
      Long guestId,
      String documentType,
      String documentNumber,
      @Nullable Instant accessExpiresAt) {}

  /** The guests pending activation (RN-32): how many, how many expired, and which one first. */
  record NotActivatedView(long count, long expired, @Nullable PendingGuestView first) {}

  /**
   * The summary of today.
   *
   * @param paymentsUpToDate the charges of today that are PAID
   * @param paymentsUnderReview those UNDER_REVIEW
   * @param paymentsPending those UNPAID, not yet overdue
   * @param paymentsOverdue those OVERDUE
   * @param pendingVouchers the vouchers awaiting review as rows of the queue, oldest first, at most
   *     {@value #MAX_PENDING_VOUCHERS}
   * @param pendingVouchersTotal how many vouchers await review in all
   * @param charges «Cobros del mes»: one per active contract and per stay in force today
   */
  record DashboardView(
      int paymentsUpToDate,
      int paymentsUnderReview,
      int paymentsPending,
      int paymentsOverdue,
      List<QueueItemView> pendingVouchers,
      long pendingVouchersTotal,
      List<ChargeView> charges,
      OverdueView overdue,
      RejectedView rejected,
      RefundsView refunds,
      ExpiringView expiringContracts,
      NotActivatedView guestsNotActivated) {}

  /**
   * One charge per active contract (the period of the current month in America/Lima, RN-10) and per
   * stay in force today; a contract that starts in a later month has no period yet and does not
   * count. The blocks of «Lo que toca ahora» and «También pendiente» look at every contract and
   * stay. Nothing to count, all zeros and empty lists.
   */
  DashboardView dashboard();
}
