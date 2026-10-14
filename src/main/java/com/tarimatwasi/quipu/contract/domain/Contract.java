package com.tarimatwasi.quipu.contract.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;

/**
 * The contract of a guest over an environment (RF-03). The dates are inclusive on both ends.
 *
 * @param endDate {@code null} for an open-ended contract (RN-07)
 * @param terminated finished by hand before its end (RN-21)
 * @param fileKey the key of the PDF in the bucket, never a URL
 */
public record Contract(
    Long id,
    Long environmentId,
    Long guestId,
    LocalDate startDate,
    @Nullable LocalDate endDate,
    BigDecimal monthlyAmount,
    int paymentDay,
    int graceDays,
    String fileKey,
    String originalName,
    long sizeBytes,
    boolean terminated) {

  /** The days left to the end date at which a contract is about to expire (RN-07). */
  public static final int EXPIRING_SOON_DAYS = 30;

  /** NUMERIC(10,2): eight digits before the point. */
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

  /** The years a PostgreSQL date and a cursor can carry without a surprise. */
  private static final int MIN_YEAR = 1900;

  private static final int MAX_YEAR = 9999;

  /** What is wrong with the terms of a contract. */
  public enum Violation {
    START_DATE("startDate"),
    END_DATE("endDate"),
    /** The end date is not after the start date. */
    DATES_ORDER("endDate"),
    MONTHLY_AMOUNT("monthlyAmount"),
    PAYMENT_DAY("paymentDay"),
    GRACE_DAYS("graceDays");

    private final String field;

    Violation(String field) {
      this.field = field;
    }

    public String field() {
      return field;
    }
  }

  /** The first thing wrong with these terms, or {@code null} if they are valid. */
  public static @Nullable Violation violationOf(
      LocalDate startDate,
      @Nullable LocalDate endDate,
      BigDecimal monthlyAmount,
      int paymentDay,
      int graceDays) {
    if (outOfRange(startDate)) {
      return Violation.START_DATE;
    }
    if (endDate != null) {
      if (outOfRange(endDate)) {
        return Violation.END_DATE;
      }
      if (!endDate.isAfter(startDate)) {
        return Violation.DATES_ORDER;
      }
    }
    if (!validAmount(monthlyAmount)) {
      return Violation.MONTHLY_AMOUNT;
    }
    if (paymentDay < 1 || paymentDay > 31) {
      return Violation.PAYMENT_DAY;
    }
    if (graceDays < 0 || graceDays > 31) {
      return Violation.GRACE_DAYS;
    }
    return null;
  }

  /** Terminated by hand, else expired once the end date has passed, else active (RN-07). */
  public ContractStatus statusAt(LocalDate today) {
    if (terminated) {
      return ContractStatus.TERMINATED;
    }
    return endDate != null && endDate.isBefore(today)
        ? ContractStatus.EXPIRED
        : ContractStatus.ACTIVE;
  }

  /**
   * Whether it holds its environment (RN-01, RN-20): every active contract does, one that starts in
   * the future as well.
   */
  public boolean occupies(LocalDate today) {
    return statusAt(today) == ContractStatus.ACTIVE;
  }

  /** Active, with an end date and {@value #EXPIRING_SOON_DAYS} days or fewer to it (RN-07). */
  public boolean expiringSoon(LocalDate today) {
    return occupies(today)
        && endDate != null
        && ChronoUnit.DAYS.between(today, endDate) <= EXPIRING_SOON_DAYS;
  }

  /** The same contract, finished by hand. */
  public Contract terminate() {
    return new Contract(
        id,
        environmentId,
        guestId,
        startDate,
        endDate,
        monthlyAmount,
        paymentDay,
        graceDays,
        fileKey,
        originalName,
        sizeBytes,
        true);
  }

  private static boolean outOfRange(LocalDate date) {
    return date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR;
  }

  /** Greater than 0, at most two decimals, and it fits NUMERIC(10,2). */
  private static boolean validAmount(BigDecimal amount) {
    return amount.signum() > 0
        && amount.stripTrailingZeros().scale() <= 2
        && amount.compareTo(MAX_AMOUNT) <= 0;
  }
}
