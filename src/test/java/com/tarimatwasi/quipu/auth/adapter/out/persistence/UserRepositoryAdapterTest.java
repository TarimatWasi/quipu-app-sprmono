package com.tarimatwasi.quipu.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TAR-38: deactivating or reactivating a guest writes only the status of its account, so a password
 * change or a failed login committed by another transaction meanwhile is not overwritten.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class UserRepositoryAdapterTest {

  @Autowired UserRepositoryAdapter adapter;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transaction;

  private final Long guestId = TestIds.next();
  private final Long accountId = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', '12345678', 'CONTRACT', 'ACTIVE')",
        guestId);
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " guest_id, must_change_password, status, failed_login_attempts) VALUES (?,"
            + " 'a@example.test', 'DNI', '12345678', 'old-hash', 'GUEST', ?, FALSE, 'ACTIVE', 0)",
        accountId,
        guestId);
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  @Test
  void theStatusChangeKeepsWhatAnotherTransactionWroteMeanwhile() {
    changeStatusAfterAnotherTransaction("INACTIVE", "hash-1", 1);
    assertThat(column("status", String.class)).isEqualTo("INACTIVE");

    changeStatusAfterAnotherTransaction("ACTIVE", "hash-2", 2);
    assertThat(column("status", String.class)).isEqualTo("ACTIVE");
  }

  /**
   * The account is first read into the persistence context; another thread then commits a new
   * password and a failed login; only then the status changes, in the same transaction as the read.
   */
  private void changeStatusAfterAnotherTransaction(String status, String hash, int attempts) {
    transaction.executeWithoutResult(
        tx -> {
          assertThat(adapter.findAllByGuestIds(List.of(guestId))).hasSize(1);
          CompletableFuture.runAsync(
                  () ->
                      jdbc.update(
                          "UPDATE users SET password_hash = ?, failed_login_attempts = ?"
                              + " WHERE id = ?",
                          hash,
                          attempts,
                          accountId))
              .join();
          adapter.setGuestAccountStatus(guestId, status);
        });
    assertThat(column("password_hash", String.class)).isEqualTo(hash);
    assertThat(column("failed_login_attempts", Integer.class)).isEqualTo(attempts);
  }

  private <T> @Nullable T column(String name, Class<T> type) {
    return jdbc.queryForObject("SELECT " + name + " FROM users WHERE id = ?", type, accountId);
  }
}
