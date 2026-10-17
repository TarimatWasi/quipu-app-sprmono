package com.tarimatwasi.quipu.payment.domain;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The state of the payment of a stay of a temporary guest (RN-03, CU-21): one payment per stay, due
 * by the exit date. There are no grace days and no periods.
 */
public final class StayPayments {

  private StayPayments() {}

  /**
   * The state of a stay with the payment it points to.
   *
   * @param paymentId the live payment, else the last rejected one, if any
   * @param rejectionReason the reason of the last rejection, only while nothing is alive
   */
  public record StayStatus(
      PeriodState state, @Nullable Long paymentId, @Nullable String rejectionReason) {}

  /**
   * PAID if a payment of the stay is approved; UNDER_REVIEW if one is pending; OVERDUE once the
   * exit date has passed without either; UNPAID before that.
   */
  public static PeriodState state(LocalDate endDate, List<Payment> ofStay, LocalDate today) {
    return status(endDate, ofStay, today).state();
  }

  /** Like {@link #state} with the payment it is read from; a rejection has no state of its own. */
  public static StayStatus status(LocalDate endDate, List<Payment> ofStay, LocalDate today) {
    Settlement settlement = Settlement.of(ofStay);
    PeriodState settled = settlement.settled();
    PeriodState state =
        settled != null
            ? settled
            : today.isAfter(endDate) ? PeriodState.OVERDUE : PeriodState.UNPAID;
    return new StayStatus(state, settlement.paymentId(), settlement.rejectionReason());
  }

  /**
   * The stay a voucher of the guest pays (CU-22): the one in course today; else the earliest one to
   * come that has no live payment; else, if every one to come has one, the earliest one to come, so
   * that the guest is told it is already paid or under review. Empty if the guest has no stay in
   * course nor to come: a past stay is not paid by the guest (the administrator registers it).
   */
  public static Optional<StayTerms> voucherStay(
      List<StayTerms> stays, Predicate<Long> hasLivePayment, LocalDate today) {
    Comparator<StayTerms> byId = Comparator.comparing(StayTerms::id);
    Optional<StayTerms> inForce = stays.stream().filter(s -> s.inForceOn(today)).min(byId);
    if (inForce.isPresent()) {
      return inForce;
    }
    List<StayTerms> coming =
        stays.stream()
            .filter(s -> s.startDate().isAfter(today))
            .sorted(Comparator.comparing(StayTerms::startDate).thenComparing(byId))
            .toList();
    return coming.stream()
        .filter(s -> !hasLivePayment.test(s.id()))
        .findFirst()
        .or(() -> coming.stream().findFirst());
  }
}
