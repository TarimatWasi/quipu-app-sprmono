package com.tarimatwasi.quipu.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Money Quipu returned to a guest from its pending credit (RF-24, RN-39, TAR-240). It counts in the
 * month of its date, taken out of that month's income. In the MVP it is not edited nor deleted.
 *
 * @param fromPaymentId the payment whose surplus it started to refund
 * @param date when the money was returned; never in the future
 * @param method how, in free text (Yape, transfer)
 */
public record Refund(
    Long id,
    Long guestId,
    Long fromPaymentId,
    BigDecimal amount,
    LocalDate date,
    String method,
    @Nullable String note,
    Instant createdAt) {

  /** The longest method, in characters. */
  public static final int MAX_METHOD = 50;

  /** The longest note, in characters. */
  public static final int MAX_NOTE = 500;
}
