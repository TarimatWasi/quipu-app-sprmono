package com.tarimatwasi.quipu.support;

import java.time.Duration;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Empties the business tables between tests. The tests of one run share a single database, and the
 * audit columns and the guest account link make the tables reference each other, so they are
 * emptied together.
 *
 * <p>The outbox delivers domain events after the commit, on other threads (audit, notifications): a
 * test may end while that work still writes. Emptying waits until every publication completed, so
 * nothing of one test lands in the next and TRUNCATE does not deadlock with a delivery. A
 * publication of a context the suite already closed may never complete: after the wait the tables
 * are emptied all the same, and its row goes with them.
 */
public final class TestTables {

  private static final String TABLES =
      "guest_document_actions, guest_document_deletions, guest_document_purges, guest_documents,"
          + " guest_consents, guest_email_verifications, access_grants, refresh_tokens, sessions,"
          + " users, guests, environments, expenses, fines, stays, contracts, refunds,"
          + " payment_reviews, payment_proofs, payments, audit_event, event_publication,"
          + " notification_reads, notifications";

  /** TRUNCATE locks every table at once; a delivery that slipped in meanwhile is retried. */
  private static final int ATTEMPTS = 5;

  private TestTables() {}

  /**
   * Removes every row of users (with their sessions, TAR-228), guests, stays, contracts, payments
   * (with their proofs and reviews), environments, expenses, fines, the audit trail, the outbox of
   * domain events and the notifications, once the outbox has nothing in flight.
   */
  public static void clear(JdbcTemplate jdbc) {
    awaitOutboxDrained(jdbc);
    for (int attempt = 1; ; attempt++) {
      try {
        jdbc.execute("TRUNCATE TABLE " + TABLES);
        return;
      } catch (PessimisticLockingFailureException deadlock) {
        if (attempt == ATTEMPTS) {
          throw deadlock;
        }
      }
    }
  }

  private static void awaitOutboxDrained(JdbcTemplate jdbc) {
    try {
      Awaitility.await("the outbox delivers every publication")
          .atMost(Duration.ofSeconds(5))
          .pollInterval(Duration.ofMillis(25))
          .until(
              () ->
                  jdbc.queryForObject(
                      "SELECT count(*) FROM event_publication WHERE completion_date IS NULL",
                      Integer.class),
              pending -> pending != null && pending == 0);
    } catch (ConditionTimeoutException orphan) {
      // A publication of a closed context: TRUNCATE removes it with the rest.
    }
  }
}
