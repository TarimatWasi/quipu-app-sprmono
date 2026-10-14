package com.tarimatwasi.quipu.payment.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * The state of the payment of a stay of a temporary guest (RN-03, CU-21): one payment per stay, due
 * by the exit date. There are no grace days and no periods.
 */
public final class StayPayments {

  private StayPayments() {}

  /**
   * PAID if a payment of the stay is approved; UNDER_REVIEW if one is pending; OVERDUE once the
   * exit date has passed without either; UNPAID before that.
   */
  public static PeriodState state(LocalDate endDate, List<Payment> ofStay, LocalDate today) {
    if (ofStay.stream().anyMatch(p -> p.status() == PaymentStatus.APPROVED)) {
      return PeriodState.PAID;
    }
    if (ofStay.stream().anyMatch(p -> p.status() == PaymentStatus.PENDING)) {
      return PeriodState.UNDER_REVIEW;
    }
    return today.isAfter(endDate) ? PeriodState.OVERDUE : PeriodState.UNPAID;
  }
}
