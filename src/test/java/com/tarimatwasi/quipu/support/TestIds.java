package com.tarimatwasi.quipu.support;

import java.util.concurrent.atomic.AtomicLong;

/** Distinct ids for the tests that build objects by hand, without a database. */
public final class TestIds {

  private static final AtomicLong NEXT = new AtomicLong(1_000_000_000L);

  private TestIds() {}

  /** An id nobody has used in this run. */
  public static Long next() {
    return NEXT.incrementAndGet();
  }
}
