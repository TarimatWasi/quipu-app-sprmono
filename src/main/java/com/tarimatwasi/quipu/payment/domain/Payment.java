package com.tarimatwasi.quipu.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A payment of a period of a contract or of a stay (RN-02, RN-03), with 1 to 5 proofs kept apart
 * (RN-22). It is created PENDING; an administrator approves it with what was verified or rejects it
 * with a reason; a rejected one is corrected and sent again (RN-05, TAR-239).
 *
 * @param contractId the contract of the period; {@code null} in the payment of a stay
 * @param stayId the stay of a temporary guest; {@code null} in the payment of a period
 * @param period the month paid; {@code null} in the payment of a stay
 * @param amount the sum of the declared amounts of its proofs
 * @param verifiedAmount the sum of the verified amounts; {@code null} until it is approved
 * @param rejectionReason the reason of the last rejection while it stays rejected (RN-05)
 * @param reviewedAt the last decision; {@code null} while pending
 * @param reviewedById who took the last decision (TAR-230); {@code null} while pending
 * @param submittedAt the last time it was sent: created, or corrected by the guest
 */
public record Payment(
    Long id,
    Long guestId,
    @Nullable Long contractId,
    @Nullable Long stayId,
    @Nullable YearMonth period,
    BigDecimal amount,
    @Nullable BigDecimal verifiedAmount,
    PaymentStatus status,
    @Nullable String rejectionReason,
    @Nullable Instant reviewedAt,
    @Nullable Long reviewedById,
    Instant submittedAt) {

  /** The longest reason of a rejection (the column and the contract say 500). */
  public static final int MAX_REASON_LENGTH = 500;

  /** RN-22: a payment gathers at most five proofs. */
  public static final int MAX_PROOFS = 5;

  /** Whitespace and Unicode separators (NBSP, EM SPACE...), which String.strip() leaves. */
  private static final Pattern EDGE_SPACE = Pattern.compile("^[\\p{Z}\\s]+|[\\p{Z}\\s]+$");

  /** NUMERIC(10,2): eight digits before the point. */
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

  /**
   * Greater than 0, at most two decimals, written without exponent, and it fits NUMERIC(10,2)
   * (RF-05).
   */
  public static boolean validAmount(BigDecimal amount) {
    return amount.signum() > 0 && validVerifiedAmount(amount);
  }

  /** Like {@link #validAmount} but 0 is allowed: a proof that turned out to be worthless. */
  public static boolean validVerifiedAmount(BigDecimal amount) {
    return amount.signum() >= 0
        && amount.scale() >= 0
        && amount.stripTrailingZeros().scale() <= 2
        && amount.compareTo(MAX_AMOUNT) <= 0;
  }

  /** The reason without the white space and separators at its edges; empty if it has none. */
  public static String cleanReason(@Nullable String reason) {
    return reason == null ? "" : EDGE_SPACE.matcher(reason).replaceAll("");
  }

  /** Whether the review is still to be done. */
  public boolean pending() {
    return status == PaymentStatus.PENDING;
  }

  /**
   * Whether an administrator can still decide on it: a pending one, or a rejected one it corrected
   * and decides on at once (TAR-239).
   */
  public boolean resolvable() {
    return status == PaymentStatus.PENDING || status == PaymentStatus.REJECTED;
  }

  /**
   * Approved by the administrator with the sum it verified (RN-16).
   *
   * @throws PaymentNotPendingException if it is approved already, or a rejection of the past
   */
  public Payment approve(Long reviewerId, BigDecimal verified, Instant at) {
    requireResolvable();
    return new Payment(
        id,
        guestId,
        contractId,
        stayId,
        period,
        amount,
        verified,
        PaymentStatus.APPROVED,
        null,
        at,
        reviewerId,
        submittedAt);
  }

  /**
   * Rejected with a reason, which is stored without the spaces at its edges. The proofs stay: the
   * guest or the administrator corrects them and the payment is sent again (RN-05).
   *
   * @throws PaymentNotPendingException if it is approved already, or a rejection of the past
   * @throws InvalidRejectionReasonException if the reason is blank or has more than 500 characters
   */
  public Payment reject(Long reviewerId, @Nullable String reason, Instant at) {
    requireResolvable();
    String clean = cleanReason(reason);
    if (clean.isEmpty() || clean.codePointCount(0, clean.length()) > MAX_REASON_LENGTH) {
      throw new InvalidRejectionReasonException();
    }
    return new Payment(
        id,
        guestId,
        contractId,
        stayId,
        period,
        amount,
        null,
        PaymentStatus.REJECTED,
        clean,
        at,
        reviewerId,
        submittedAt);
  }

  /**
   * The proofs changed: the new sum of what they declare. The guest corrects only a rejected
   * payment and sends it again, back to PENDING with a new submission date; the administrator
   * corrects a pending or rejected one and its status stays (TAR-239).
   *
   * @param resubmitted whether the correction sends it again (the guest's)
   * @throws CorrectionNotAllowedException if the payment cannot be corrected in its status
   */
  public Payment corrected(BigDecimal declared, boolean resubmitted, Instant at) {
    if (resubmitted ? status != PaymentStatus.REJECTED : !resolvable()) {
      throw new CorrectionNotAllowedException();
    }
    if (!resubmitted) {
      return new Payment(
          id,
          guestId,
          contractId,
          stayId,
          period,
          declared,
          verifiedAmount,
          status,
          rejectionReason,
          reviewedAt,
          reviewedById,
          submittedAt);
    }
    return new Payment(
        id,
        guestId,
        contractId,
        stayId,
        period,
        declared,
        null,
        PaymentStatus.PENDING,
        null,
        null,
        null,
        at);
  }

  private void requireResolvable() {
    if (!resolvable()) {
      throw new PaymentNotPendingException();
    }
  }
}
