package com.tarimatwasi.quipu.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Empties the business tables between tests. The tests of one run share a single database, and the
 * audit columns and the guest account link make the tables reference each other, so they are
 * emptied together.
 */
public final class TestTables {

  private TestTables() {}

  /** Removes every row of users, guests, environments and expenses. */
  public static void clear(JdbcTemplate jdbc) {
    jdbc.execute("TRUNCATE TABLE users, guests, environments, expenses");
  }
}
