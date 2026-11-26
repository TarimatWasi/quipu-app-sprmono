package com.tarimatwasi.quipu.shared.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AfterTransactionTest {
  private final List<String> ran = new ArrayList<>();

  @AfterEach
  void endTheTransaction() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  /** Ends the simulated transaction with the given status, as the transaction manager would. */
  private static void end(int status) {
    List<TransactionSynchronization> registered =
        TransactionSynchronizationManager.getSynchronizations();
    if (status == TransactionSynchronization.STATUS_COMMITTED) {
      registered.forEach(TransactionSynchronization::afterCommit);
    }
    registered.forEach(synchronization -> synchronization.afterCompletion(status));
  }

  @ParameterizedTest
  @CsvSource({"0, ''", "1, rolled back", "2, unknown"})
  void unlessCommitted_runsOnlyWhatTheOutcomeAsks(int status, String expected) {
    TransactionSynchronizationManager.initSynchronization();

    boolean registered =
        AfterTransaction.unlessCommitted(() -> ran.add("rolled back"), () -> ran.add("unknown"));
    assertThat(ran).isEmpty();
    end(status);

    assertThat(registered).isTrue();
    assertThat(ran).isEqualTo(expected.isEmpty() ? List.of() : List.of(expected));
  }

  @Test
  void unlessCommitted_withoutTransaction_leavesItToTheCaller() {
    assertThat(AfterTransaction.unlessCommitted(() -> ran.add("x"), () -> ran.add("y"))).isFalse();
    assertThat(ran).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"0, true", "1, false", "2, false"})
  void onceCommitted_runsOnlyAfterACommit(int status, boolean runs) {
    TransactionSynchronizationManager.initSynchronization();

    AfterTransaction.onceCommitted(() -> ran.add("committed"));
    assertThat(ran).isEmpty();
    end(status);

    assertThat(ran).hasSize(runs ? 1 : 0);
  }

  @Test
  void onceCommitted_withoutTransaction_runsAtOnce() {
    AfterTransaction.onceCommitted(() -> ran.add("now"));

    assertThat(ran).containsExactly("now");
  }
}
