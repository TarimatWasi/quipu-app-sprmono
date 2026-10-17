package com.tarimatwasi.quipu.payment.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** What the payments need to know of a stay of a temporary guest; the dates are inclusive. */
public record StayTerms(Long id, LocalDate startDate, LocalDate endDate, BigDecimal agreedAmount) {

  /** Whether the stay is in course on that day. */
  public boolean inForceOn(LocalDate day) {
    return !startDate.isAfter(day) && !endDate.isBefore(day);
  }
}
