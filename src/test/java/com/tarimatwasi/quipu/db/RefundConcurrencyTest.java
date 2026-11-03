package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.payment.application.port.in.InvalidRefundException;
import com.tarimatwasi.quipu.payment.application.port.in.ManageRefundsUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.ManageRefundsUseCase.RefundCommand;
import com.tarimatwasi.quipu.support.PaymentRows;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * TAR-240 (RN-38): two refunds of one guest registered at the same time never return more than its
 * pending credit; the guest is locked, so the second one sees the first and answers {@code amount}.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class RefundConcurrencyTest {

  private static final int ROUNDS = 5;

  @Autowired ManageRefundsUseCase refunds;
  @Autowired JdbcTemplate jdbc;

  private Long admin;
  private Long guest;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    admin = TestIds.next();
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, 'admin@example.test', 'DNI', '00000001', 'hash', 'ADMIN', FALSE, 'ACTIVE')",
        admin);
    guest = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', '80000002', 'TEMPORARY', 'ACTIVE')",
        guest);
    Long stay = TestIds.next();
    jdbc.update(
        "INSERT INTO stays (id, guest_id, start_date, end_date, agreed_amount)"
            + " VALUES (?, ?, '2026-09-01', '2026-09-10', 150)",
        stay,
        guest);
    // Paid 250 for a stay of 150: 100 to refund
    PaymentRows.insert(
        jdbc,
        new PaymentRows.Row(
            TestIds.next(),
            guest,
            null,
            stay,
            null,
            "250",
            "APPROVED",
            admin,
            "ADMIN",
            null,
            "vouchers/" + stay,
            "application/pdf"));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    TestTables.clear(jdbc);
  }

  @Test
  void twoRefundsAtOnceNeverReturnMoreThanThePendingCredit() {
    for (int round = 0; round < ROUNDS; round++) {
      List<Optional<Throwable>> outcomes = race(this::refundSixty, this::refundSixty);

      assertThat(outcomes.stream().filter(Optional::isEmpty).count()).as("one refund").isEqualTo(1);
      assertThat(
              outcomes.stream()
                  .flatMap(Optional::stream)
                  .filter(InvalidRefundException.class::isInstance)
                  .map(e -> ((InvalidRefundException) e).field()))
          .containsExactly("amount");
      assertThat(jdbc.queryForObject("SELECT sum(amount) FROM refunds", BigDecimal.class))
          .isEqualByComparingTo("60");
      jdbc.update("DELETE FROM refunds");
    }
  }

  private void refundSixty() {
    refunds.create(
        new RefundCommand(
            guest, new BigDecimal("60"), LocalDate.parse("2026-10-01"), "Yape", null));
  }

  /** What each of two requests that start together ends with: nothing, or the exception it got. */
  private List<Optional<Throwable>> race(Runnable first, Runnable second) {
    var barrier = new CyclicBarrier(2);
    var a = start(barrier, first);
    var b = start(barrier, second);
    return List.of(a.join(), b.join());
  }

  private CompletableFuture<Optional<Throwable>> start(CyclicBarrier barrier, Runnable request) {
    return CompletableFuture.runAsync(() -> run(barrier, request))
        .handle(
            (done, failure) ->
                Optional.ofNullable(failure).map(f -> Objects.requireNonNullElse(f.getCause(), f)));
  }

  private void run(CyclicBarrier barrier, Runnable request) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(admin.toString(), "x", "ROLE_ADMIN"));
    try {
      barrier.await();
      request.run();
    } catch (InterruptedException | BrokenBarrierException broken) {
      throw new IllegalStateException(broken);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
