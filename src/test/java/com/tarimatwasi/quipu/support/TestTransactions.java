package com.tarimatwasi.quipu.support;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * Runs code as inside a transaction and then ends it with the outcome asked, as the transaction
 * manager would: what was registered for after the transaction (AfterTransaction) runs with it. An
 * unknown outcome is the case no real database gives on demand: the commit was sent and its answer
 * lost.
 */
public final class TestTransactions {

  private TestTransactions() {}

  /** Runs the action in a transaction that ends with the status; rethrows what it threw. */
  public static void endingWith(int status, Runnable action) {
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      try {
        action.run();
      } finally {
        TransactionSynchronizationUtils.invokeAfterCompletion(
            TransactionSynchronizationManager.getSynchronizations(), status);
      }
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  /** The commit was sent and its answer lost. */
  public static final int UNKNOWN = TransactionSynchronization.STATUS_UNKNOWN;

  /** The transaction rolled back. */
  public static final int ROLLED_BACK = TransactionSynchronization.STATUS_ROLLED_BACK;
}
