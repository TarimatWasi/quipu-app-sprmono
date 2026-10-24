package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.guest.port.in.LockGuestUseCase;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;

/** TAR-204: a row lock lives only inside a transaction and never waits without an end. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class LockBoundariesTest {

  @Autowired LockGuestUseCase locks;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void clearSession() {
    SecurityContextHolder.clearContext();
  }

  /** Outside a transaction the lock of the guest would end at once: it is refused. */
  @Test
  void lockingAGuestOutsideATransactionIsRefused() {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("1", "x", "ROLE_ADMIN"));

    assertThatThrownBy(() -> locks.lock(TestIds.next()))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  /** Every connection of the application waits for a row lock 5 s at most. */
  @Test
  void theConnectionsOfTheApplicationHaveALockTimeout() {
    assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("5s");
  }
}
