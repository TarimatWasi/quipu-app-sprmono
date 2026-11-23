package com.tarimatwasi.quipu.shared.transaction;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Work outside the database that depends on how the current transaction ends: an object stored in
 * the bucket is removed if its row never commits, and an object a change replaced is removed only
 * once the change commits. The only place that registers such work (QP-TX-01).
 *
 * <p>An outcome may be unknown (the connection broke during the commit): the row may exist, so its
 * object is never removed then; the caller decides how to leave it traced.
 */
public final class AfterTransaction {
  private AfterTransaction() {}

  /**
   * Inside a transaction, runs {@code rolledBack} if it rolls back and {@code unknown} if its
   * outcome is unknown; nothing if it commits.
   *
   * @return false with no transaction: the caller handles the failure itself
   */
  public static boolean unlessCommitted(Runnable rolledBack, Runnable unknown) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return false;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status == STATUS_ROLLED_BACK) {
              rolledBack.run();
            } else if (status == STATUS_UNKNOWN) {
              unknown.run();
            }
          }
        });
    return true;
  }

  /** Runs once the transaction commits; with no transaction, at once. */
  public static void onceCommitted(Runnable committed) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      committed.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            committed.run();
          }
        });
  }
}
