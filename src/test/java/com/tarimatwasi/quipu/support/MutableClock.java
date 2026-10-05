package com.tarimatwasi.quipu.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A clock the tests move forward. */
public final class MutableClock extends Clock {
  private Instant now;

  public MutableClock(Instant start) {
    this.now = start;
  }

  public void advance(Duration by) {
    now = now.plus(by);
  }

  @Override
  public ZoneId getZone() {
    return ZoneId.of("America/Lima");
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return now;
  }
}
