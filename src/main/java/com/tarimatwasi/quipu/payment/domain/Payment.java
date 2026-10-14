package com.tarimatwasi.quipu.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A voucher with its declared amount, for a period of a contract or for a stay (RN-02, RN-03). It
 * is created PENDING and resolved once by an administrator.
 *
 * @param contractId the contract of the period; {@code null} in the payment of a stay
 * @param stayId the stay of a temporary guest; {@code null} in the payment of a period
 * @param period the month paid; {@code null} in the payment of a stay
 * @param rejectionReason shown to the guest in the app only (RN-05)
 */
public record Payment(
    Long id,
    Long guestId,
    @Nullable Long contractId,
    @Nullable Long stayId,
    @Nullable YearMonth period,
    BigDecimal amount,
    @Nullable String paymentMethod,
    PaymentStatus status,
    @Nullable String rejectionReason,
    @Nullable Instant reviewedAt,
    @Nullable Long reviewedById) {

  /** The longest reason of a rejection (the column and the contract say 500). */
  public static final int MAX_REASON_LENGTH = 500;

  /** Whitespace and Unicode separators (NBSP, EM SPACE...), which String.strip() leaves. */
  private static final Pattern EDGE_SPACE = Pattern.compile("^[\\p{Z}\\s]+|[\\p{Z}\\s]+$");

  /** NUMERIC(10,2): eight digits before the point. */
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

  /**
   * Greater than 0, at most two decimals, written without exponent, and it fits NUMERIC(10,2)
   * (RF-05).
   */
  public static boolean validAmount(BigDecimal amount) {
    return amount.signum() > 0
        && amount.scale() >= 0
        && amount.stripTrailingZeros().scale() <= 2
        && amount.compareTo(MAX_AMOUNT) <= 0;
  }

  /** Whether the review is still to be done. */
  public boolean pending() {
    return status == PaymentStatus.PENDING;
  }

  /** Whether the payment counts as paid or as waiting, so that the guest cannot upload another. */
  public boolean alive() {
    return status != PaymentStatus.REJECTED;
  }

  /**
   * Approved by the administrator.
   *
   * @throws PaymentNotPendingException if it was already approved or rejected
   */
  public Payment approve(Long reviewerId, Instant at) {
    requirePending();
    return new Payment(
        id,
        guestId,
        contractId,
        stayId,
        period,
        amount,
        paymentMethod,
        PaymentStatus.APPROVED,
        null,
        at,
        reviewerId);
  }

  /**
   * Rejected with a reason, which is stored without the spaces at its edges. The period or the stay
   * stays open: the guest can upload a new voucher (RN-05).
   *
   * @throws PaymentNotPendingException if it was already approved or rejected
   * @throws InvalidRejectionReasonException if the reason is blank or has more than 500 characters
   */
  public Payment reject(Long reviewerId, @Nullable String reason, Instant at) {
    requirePending();
    String clean = reason == null ? "" : EDGE_SPACE.matcher(reason).replaceAll("");
    if (clean.isEmpty() || clean.length() > MAX_REASON_LENGTH) {
      throw new InvalidRejectionReasonException();
    }
    return new Payment(
        id,
        guestId,
        contractId,
        stayId,
        period,
        amount,
        paymentMethod,
        PaymentStatus.REJECTED,
        clean,
        at,
        reviewerId);
  }

  private void requirePending() {
    if (!pending()) {
      throw new PaymentNotPendingException();
    }
  }
}
