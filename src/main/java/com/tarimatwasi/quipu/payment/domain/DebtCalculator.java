package com.tarimatwasi.quipu.payment.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * The debt of a guest (RN-25, RN-35): the periods whose due date has passed, WITHOUT the grace
 * days, and that have no approved payment. A voucher under review does not clear it. The grace days
 * only decide when a period is shown as overdue; they do not delay the debt.
 */
public final class DebtCalculator {

  private DebtCalculator() {}

  /** The periods in debt, oldest first. */
  public static List<DebtPeriod> debt(
      ContractTerms terms, List<Payment> payments, LocalDate today) {
    return terms.periods(today).stream()
        .filter(period -> terms.dueDate(period).isBefore(today))
        .filter(
            period ->
                payments.stream()
                    .noneMatch(
                        p -> period.equals(p.period()) && p.status() == PaymentStatus.APPROVED))
        .map(period -> new DebtPeriod(period, terms.dueDate(period)))
        .toList();
  }
}
