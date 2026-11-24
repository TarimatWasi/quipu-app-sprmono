package com.tarimatwasi.quipu.support;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Who waits for whom in PostgreSQL, for the tests that prove a lock serializes two transactions.
 * The only reader of pg_stat_activity in the tests (LockWaitsTest guards it): inside a transaction
 * that view is a snapshot taken at its first read, so a poll that does not clear it can miss a wait
 * that began later and pass or fail by timing alone.
 */
public final class LockWaits {
  private static final Duration PATIENCE = Duration.ofSeconds(10);

  private LockWaits() {}

  /** The PostgreSQL backend of the connection of the calling transaction. */
  public static int backendPid(JdbcTemplate jdbc) {
    return Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
  }

  /** Someone of this database waits for a lock that the given backend holds. */
  public static boolean someoneWaitsOn(JdbcTemplate jdbc, int holder) {
    clearSnapshot(jdbc);
    Integer waiting =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_activity a"
                + " WHERE a.wait_event_type = 'Lock' AND a.datname = current_database()"
                + " AND ? = ANY (pg_blocking_pids(a.pid))",
            Integer.class,
            holder);
    return waiting != null && waiting > 0;
  }

  /** The given backend holds a granted lock on the table (pg_locks is never a snapshot). */
  public static boolean holdsALockOn(JdbcTemplate jdbc, int holder, String table) {
    Boolean holds =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pg_locks l"
                + " WHERE l.pid = ? AND l.relation = ?::regclass AND l.granted)",
            Boolean.class,
            holder,
            table);
    return Boolean.TRUE.equals(holds);
  }

  /** How many sessions of this database wait for a lock, whoever holds it. */
  public static int waitingForALock(JdbcTemplate jdbc) {
    clearSnapshot(jdbc);
    Integer waiting =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_activity"
                + " WHERE wait_event_type = 'Lock' AND datname = current_database()",
            Integer.class);
    return waiting == null ? 0 : waiting;
  }

  /**
   * Polls until the condition holds; gives up after ten seconds or when the thread is interrupted
   * (the pool is shut down after a failed test).
   */
  public static boolean until(BooleanSupplier condition) {
    long deadline = System.nanoTime() + PATIENCE.toNanos();
    while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
      if (condition.getAsBoolean()) {
        return true;
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
    }
    return false;
  }

  private static void clearSnapshot(JdbcTemplate jdbc) {
    jdbc.queryForList("SELECT pg_stat_clear_snapshot()");
  }
}
