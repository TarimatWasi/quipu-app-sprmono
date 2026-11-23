package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.ServiceBusy;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * v0.29.0 (TAR-204): the failures the BFF answers 503 SERVICE_BUSY are recognized whichever way the
 * request read the database (JDBC, JPA, or no connection at all), each wrapped differently.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class BusyDatabaseTest {

  @Autowired JdbcTemplate jdbc;
  @Autowired JdbcClient client;
  @Autowired TransactionTemplate transaction;
  @Autowired GuestRepositoryPort guests;
  @Autowired HikariDataSource pool;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
  }

  /** A statement past statement_timeout (57014) by JDBC. */
  @Test
  void aStatementPastItsLimitByJdbcIsAQueryTimeout() {
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      client.sql("SET LOCAL statement_timeout = '100ms'").update();
                      client.sql("SELECT pg_sleep(2)").query().listOfRows();
                    }))
        .isInstanceOf(QueryTimeoutException.class)
        .matches(ServiceBusy::causedBy, "is a busy database");
  }

  /** The same by JPA, through a repository: the statement waits for a row lock past its limit. */
  @Test
  void aStatementPastItsLimitByJpaIsAQueryTimeout() throws Exception {
    Long guestId = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', '12345678', 'CONTRACT', 'ACTIVE')",
        guestId);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> holder =
        CompletableFuture.runAsync(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      jdbc.queryForList("SELECT id FROM guests WHERE id = ? FOR UPDATE", guestId);
                      locked.countDown();
                      try {
                        release.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }));
    try {
      locked.await(10, TimeUnit.SECONDS);

      assertThatThrownBy(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        client.sql("SET LOCAL statement_timeout = '200ms'").update();
                        guests.findByIdForUpdate(guestId);
                      }))
          .isInstanceOf(QueryTimeoutException.class)
          .matches(ServiceBusy::causedBy, "is a busy database");
    } finally {
      release.countDown();
      holder.get(10, TimeUnit.SECONDS);
    }
  }

  /** No free connection within the wait of the pool: the transaction never starts. */
  @Test
  void noFreeConnectionMeansTheTransactionCannotStart() throws SQLException {
    List<Connection> all = new ArrayList<>();
    try {
      for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
        all.add(pool.getConnection());
      }

      assertThatThrownBy(() -> transaction.executeWithoutResult(status -> guests.findById(1L)))
          .isInstanceOf(CannotCreateTransactionException.class)
          .matches(ServiceBusy::causedBy, "is a busy database");
    } finally {
      for (Connection connection : all) {
        connection.close();
      }
    }
  }

  /** Outside a transaction a repository of JPA wraps the same wait differently. */
  @Test
  void noFreeConnectionOutsideATransactionIsRecognizedToo() throws SQLException {
    List<Connection> all = new ArrayList<>();
    try {
      for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
        all.add(pool.getConnection());
      }

      assertThatThrownBy(() -> guests.findById(1L)).matches(ServiceBusy::causedBy, "is busy");
    } finally {
      for (Connection connection : all) {
        connection.close();
      }
    }
  }
}
