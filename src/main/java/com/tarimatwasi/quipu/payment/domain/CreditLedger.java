package com.tarimatwasi.quipu.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * RN-38 (TAR-239, TAR-240): the credit of one contract, derived on the day it is asked and never
 * stored. The periods are read oldest first; each one takes the credit left by the ones before, at
 * most its monthly amount, and what is left to pay is the rest. An approved payment that verified
 * more than that leaves the difference as credit for the next period. A period the credit covers
 * whole is PAID without a payment, and what is left passes on to the next one, month by month
 * (decided 2026-11-01). The credit left once the last period of the contract is reached (its end or
 * its termination) has nowhere to be used: Quipu refunds it (RN-39). The credit is taken oldest
 * first, so what is left of it is what the newest payments left over (TAR-284).
 *
 * @param lines every period the contract charges up to today, oldest first
 * @param carry the credit left after the last period read
 * @param over whether the last period of the contract was reached, so the carry is refunded
 * @param carryPaymentId the last payment that left credit, if any: where the carry comes from
 * @param leftovers the carry by the payment it was left over from, oldest first; they add up to it
 */
public record CreditLedger(
    List<Line> lines,
    BigDecimal carry,
    boolean over,
    @Nullable Long carryPaymentId,
    List<GuestCredit.UsableOrigin> leftovers) {

  private static final int SCALE = 2;

  public CreditLedger {
    lines = List.copyOf(lines);
    leftovers = List.copyOf(leftovers);
  }

  /**
   * One period with what the credit did to it.
   *
   * @param creditApplied the credit discounted from it, at most its amount
   * @param amountDue {@code amount - creditApplied}: what the verified sum must cover
   * @param last whether it is the last period of the contract
   * @param surplus what its approved payment verified over {@code amountDue}; 0 when none
   */
  public record Line(
      PeriodStatus status,
      BigDecimal amount,
      BigDecimal creditApplied,
      BigDecimal amountDue,
      boolean last,
      @Nullable Long approvedPaymentId,
      BigDecimal surplus) {

    public YearMonth period() {
      return status.period();
    }
  }

  /** What a payment left over that no period took yet; only {@link #of} changes it. */
  private static final class Leftover {
    private final Long paymentId;
    private final YearMonth period;
    private BigDecimal amount;

    private Leftover(Long paymentId, YearMonth period, BigDecimal amount) {
      this.paymentId = paymentId;
      this.period = period;
      this.amount = amount;
    }
  }

  /** The ledger of a contract from all its payments; those of other contracts are not read. */
  public static CreditLedger of(
      ContractTerms terms, BigDecimal monthlyAmount, List<Payment> payments, LocalDate today) {
    BigDecimal monthly = cents(monthlyAmount);
    BigDecimal carry = cents(BigDecimal.ZERO);
    Long carryPaymentId = null;
    YearMonth lastPeriod = terms.lastPeriod();
    Deque<Leftover> leftovers = new ArrayDeque<>();
    List<Line> lines = new ArrayList<>();
    for (YearMonth period : terms.periods(today)) {
      PeriodStatus status = terms.status(period, payments, today);
      BigDecimal applied = carry.min(monthly);
      BigDecimal due = monthly.subtract(applied);
      carry = carry.subtract(applied);
      take(leftovers, applied);
      if (due.signum() == 0 && status.state() != PeriodState.PAID) {
        // Covered by the credit alone: paid without a payment of its own.
        status =
            new PeriodStatus(period, status.dueDate(), PeriodState.PAID, status.paymentId(), null);
      }
      Payment approved = approvedOf(period, payments);
      BigDecimal surplus = cents(BigDecimal.ZERO);
      BigDecimal verified = approved == null ? null : approved.verifiedAmount();
      if (approved != null && verified != null) {
        surplus = cents(verified).subtract(due).max(cents(BigDecimal.ZERO));
        if (surplus.signum() > 0) {
          carry = carry.add(surplus);
          carryPaymentId = approved.id();
          leftovers.addLast(new Leftover(approved.id(), period, surplus));
        }
      }
      lines.add(
          new Line(
              status,
              monthly,
              applied,
              due,
              period.equals(lastPeriod),
              approved == null ? null : approved.id(),
              surplus));
    }
    boolean over = lastPeriod != null && !lastPeriod.isAfter(YearMonth.from(today));
    List<GuestCredit.UsableOrigin> left =
        leftovers.stream()
            .map(l -> new GuestCredit.UsableOrigin(l.paymentId, l.period, l.amount))
            .toList();
    return new CreditLedger(lines, carry, over, carryPaymentId, left);
  }

  /** A period takes the credit oldest first (TAR-284). */
  private static void take(Deque<Leftover> leftovers, BigDecimal applied) {
    BigDecimal toTake = applied;
    while (toTake.signum() > 0 && !leftovers.isEmpty()) {
      Leftover oldest = leftovers.getFirst();
      BigDecimal taken = oldest.amount.min(toTake);
      oldest.amount = oldest.amount.subtract(taken);
      toTake = toTake.subtract(taken);
      if (oldest.amount.signum() == 0) {
        leftovers.removeFirst();
      }
    }
  }

  /** The line of a period, if the contract charges it today. */
  public @Nullable Line line(YearMonth period) {
    return lines.stream().filter(l -> l.period().equals(period)).findFirst().orElse(null);
  }

  /**
   * The credit a period would take if the contract charged it: the carry at the end, for a period
   * after the ones read (a renewal loaded ahead of time, or the next month).
   */
  public BigDecimal creditFor(YearMonth period, BigDecimal monthlyAmount) {
    Line line = line(period);
    return line != null ? line.creditApplied() : carry.min(cents(monthlyAmount));
  }

  /** The credit still to use: the carry while the contract has periods left, else 0. */
  public BigDecimal usable() {
    return over ? cents(BigDecimal.ZERO) : carry;
  }

  /**
   * The payments the credit still to use was left over from, oldest first, with what is left of
   * each one; none once the last period was reached, when the carry is to refund (TAR-284).
   */
  public List<GuestCredit.UsableOrigin> usableOrigins() {
    return over ? List.of() : leftovers;
  }

  /** The credit to refund: the carry once the last period was reached, else 0. */
  public BigDecimal refundable() {
    return over ? carry : cents(BigDecimal.ZERO);
  }

  private static @Nullable Payment approvedOf(YearMonth period, List<Payment> payments) {
    return payments.stream()
        .filter(p -> period.equals(p.period()) && p.status() == PaymentStatus.APPROVED)
        .min(Comparator.comparing(Payment::id))
        .orElse(null);
  }

  static BigDecimal cents(BigDecimal amount) {
    return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
  }
}
