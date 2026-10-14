package com.tarimatwasi.quipu.payment.domain;

import java.time.Clock;
import java.time.LocalDate;

/** The day of the business: the calendar day in the zone of the injected clock (America/Lima). */
public final class BusinessDay {

  private BusinessDay() {}

  /** Today, as a calendar day of the zone of the clock and not of UTC. */
  public static LocalDate today(Clock clock) {
    return LocalDate.ofInstant(clock.instant(), clock.getZone());
  }
}
