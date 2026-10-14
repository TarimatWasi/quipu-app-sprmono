package com.tarimatwasi.quipu.payment.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the payments need to know of a contract: from which month it charges, up to which, and when
 * a period is due (RN-02, RN-10). There is no prorating: the first and the last month are whole
 * periods. Everything is derived on the day it is asked, so the caller passes the day of Lima.
 *
 * @param endDate {@code null} for an open-ended contract
 * @param terminatedIn the month in which an administrator finished the contract by hand, if it was
 */
public record ContractTerms(
    LocalDate startDate,
    @Nullable LocalDate endDate,
    @Nullable YearMonth terminatedIn,
    int paymentDay,
    int graceDays) {

  /**
   * The terms of a contract as the contract module keeps them: the month of the termination is the
   * one of that instant in the zone of the business (America/Lima), so a contract finished at 22:00
   * of the last day of a month in Lima, already the next month in UTC, still charges that month and
   * not the next.
   */
  public static ContractTerms of(
      LocalDate startDate,
      @Nullable LocalDate endDate,
      @Nullable Instant terminatedAt,
      ZoneId zone,
      int paymentDay,
      int graceDays) {
    YearMonth terminatedIn =
        terminatedAt == null ? null : YearMonth.from(terminatedAt.atZone(zone));
    return new ContractTerms(startDate, endDate, terminatedIn, paymentDay, graceDays);
  }

  /**
   * The months that charge, from the one of the start date up to the earliest of the current month,
   * the month of the end date and the month of the termination. A contract that starts in a later
   * month has none yet.
   */
  public List<YearMonth> periods(LocalDate today) {
    YearMonth last = YearMonth.from(today);
    if (endDate != null && YearMonth.from(endDate).isBefore(last)) {
      last = YearMonth.from(endDate);
    }
    if (terminatedIn != null && terminatedIn.isBefore(last)) {
      last = terminatedIn;
    }
    List<YearMonth> periods = new ArrayList<>();
    for (YearMonth month = YearMonth.from(startDate);
        !month.isAfter(last);
        month = month.plusMonths(1)) {
      periods.add(month);
    }
    return periods;
  }

  /**
   * Whether a voucher may be registered for the period (RN-27): the period must be one the contract
   * charges up to today. A contract in force takes any of them. One that expired by date takes only
   * the most recent period still in debt, which the administrator registers by hand. One finished
   * by hand takes none, and one that has not started has no period yet.
   */
  public boolean acceptsPayment(YearMonth period, List<Payment> payments, LocalDate today) {
    if (!periods(today).contains(period)) {
      return false;
    }
    if (terminatedIn != null) {
      return false;
    }
    if (endDate == null || !endDate.isBefore(today)) {
      return true;
    }
    List<DebtPeriod> debt = DebtCalculator.debt(this, payments, today);
    return !debt.isEmpty() && debt.get(debt.size() - 1).period().equals(period);
  }

  /** The payment day of the month, or its last day when the month is shorter (RN-10). */
  public LocalDate dueDate(YearMonth period) {
    return period.atDay(Math.min(paymentDay, period.lengthOfMonth()));
  }

  /** The state of each charged period, oldest first, from the payments of the contract. */
  public List<PeriodStatus> statuses(List<Payment> payments, LocalDate today) {
    return periods(today).stream().map(period -> status(period, payments, today)).toList();
  }

  /**
   * One period: PAID if a payment is approved; else UNDER_REVIEW if one is pending; else OVERDUE
   * once the due date plus the grace days have passed; else UNPAID. A rejected voucher does not
   * close the period (RN-05).
   */
  public PeriodStatus status(YearMonth period, List<Payment> payments, LocalDate today) {
    List<Payment> ofPeriod = payments.stream().filter(p -> period.equals(p.period())).toList();
    LocalDate due = dueDate(period);
    Payment approved = first(ofPeriod, PaymentStatus.APPROVED);
    if (approved != null) {
      return new PeriodStatus(period, due, PeriodState.PAID, approved.id(), null);
    }
    Payment pending = first(ofPeriod, PaymentStatus.PENDING);
    if (pending != null) {
      return new PeriodStatus(period, due, PeriodState.UNDER_REVIEW, pending.id(), null);
    }
    Payment rejected = last(ofPeriod, PaymentStatus.REJECTED);
    PeriodState state =
        today.isAfter(due.plusDays(graceDays)) ? PeriodState.OVERDUE : PeriodState.UNPAID;
    return new PeriodStatus(
        period,
        due,
        state,
        rejected == null ? null : rejected.id(),
        rejected == null ? null : rejected.rejectionReason());
  }

  private static @Nullable Payment first(List<Payment> payments, PaymentStatus status) {
    return payments.stream()
        .filter(p -> p.status() == status)
        .min(Comparator.comparing(Payment::id))
        .orElse(null);
  }

  private static @Nullable Payment last(List<Payment> payments, PaymentStatus status) {
    return payments.stream()
        .filter(p -> p.status() == status)
        // The newest review, not the highest id: ids come from blocks of 50 that each instance
        // reserves, so a later retry can have a lower id than an earlier voucher.
        .max(
            Comparator.comparing(
                    Payment::reviewedAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
                .thenComparing(Payment::id))
        .orElse(null);
  }
}
