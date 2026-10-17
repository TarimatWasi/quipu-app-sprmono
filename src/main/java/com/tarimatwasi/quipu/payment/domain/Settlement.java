package com.tarimatwasi.quipu.payment.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the payments of one period or one stay say before the dates are read: PAID if one is
 * approved, else UNDER_REVIEW if one is pending; else nothing settles it, and the last rejection is
 * kept with its reason (D-34d). A rejected voucher never gives a state of its own (RN-05).
 *
 * @param settled PAID or UNDER_REVIEW; {@code null} if nothing is alive
 * @param paymentId the live payment, else the last rejected one, if any
 * @param rejectionReason the reason of the last rejection, only while nothing is alive
 */
public record Settlement(
    @Nullable PeriodState settled, @Nullable Long paymentId, @Nullable String rejectionReason) {

  /** The settlement of the payments of one period or one stay. */
  public static Settlement of(List<Payment> payments) {
    Payment approved = first(payments, PaymentStatus.APPROVED);
    if (approved != null) {
      return new Settlement(PeriodState.PAID, approved.id(), null);
    }
    Payment pending = first(payments, PaymentStatus.PENDING);
    if (pending != null) {
      return new Settlement(PeriodState.UNDER_REVIEW, pending.id(), null);
    }
    Payment rejected = last(payments, PaymentStatus.REJECTED);
    return rejected == null
        ? new Settlement(null, null, null)
        : new Settlement(null, rejected.id(), rejected.rejectionReason());
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
