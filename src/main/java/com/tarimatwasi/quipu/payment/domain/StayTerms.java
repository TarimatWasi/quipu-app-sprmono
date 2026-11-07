package com.tarimatwasi.quipu.payment.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * What the payments need to know of a stay of a temporary guest; the dates are inclusive.
 *
 * @param environmentId the environment the stay occupies; {@code null} for an old stay without one
 *     (TAR-237)
 */
public record StayTerms(
    Long id,
    LocalDate startDate,
    LocalDate endDate,
    BigDecimal agreedAmount,
    @Nullable Long environmentId) {

  /** A stay whose environment the payments do not need. */
  public StayTerms(Long id, LocalDate startDate, LocalDate endDate, BigDecimal agreedAmount) {
    this(id, startDate, endDate, agreedAmount, null);
  }

  /** Whether the stay is in course on that day. */
  public boolean inForceOn(LocalDate day) {
    return !startDate.isAfter(day) && !endDate.isBefore(day);
  }
}
