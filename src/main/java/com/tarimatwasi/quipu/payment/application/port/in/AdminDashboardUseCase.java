package com.tarimatwasi.quipu.payment.application.port.in;

import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentView;
import java.util.List;

/** HU-A04, CU-04: the home of the administrator; only the administrator reads it. */
public interface AdminDashboardUseCase {

  /** The most vouchers awaiting review that the summary lists. */
  int MAX_PENDING_VOUCHERS = 20;

  /**
   * The summary of today.
   *
   * @param paymentsUpToDate the charges of today that are PAID
   * @param paymentsUnderReview those UNDER_REVIEW
   * @param paymentsPending those UNPAID, not yet overdue
   * @param paymentsOverdue those OVERDUE
   * @param pendingVouchers the vouchers awaiting review, oldest first, at most {@value
   *     #MAX_PENDING_VOUCHERS}
   * @param pendingVouchersTotal how many vouchers await review in all
   */
  record DashboardView(
      int paymentsUpToDate,
      int paymentsUnderReview,
      int paymentsPending,
      int paymentsOverdue,
      List<PaymentView> pendingVouchers,
      long pendingVouchersTotal) {}

  /**
   * One state per active contract (the period of the current month in America/Lima, RN-10) and per
   * temporary guest with a stay in course today; a contract that starts in a later month has no
   * period yet and does not count. Nothing to count, all zeros and no vouchers.
   */
  DashboardView dashboard();
}
