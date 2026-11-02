package com.tarimatwasi.quipu.payment.domain;

import java.math.BigDecimal;

/**
 * What the movements of one kind add up to in a month and how many they are (RF-18, the A6
 * breakdown, TAR-240).
 */
public record MonthTotal(BigDecimal amount, long count) {

  /** No movements. */
  public static final MonthTotal NONE = new MonthTotal(BigDecimal.ZERO, 0);

  /** Both totals together. */
  public MonthTotal plus(MonthTotal other) {
    return new MonthTotal(amount.add(other.amount), count + other.count);
  }
}
