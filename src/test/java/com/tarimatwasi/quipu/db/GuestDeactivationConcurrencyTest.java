package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.guest.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.DirectPayment;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.VoucherUpload;
import com.tarimatwasi.quipu.payment.port.out.PaymentFilePort;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TAR-38 (D-27): an inactive guest pays only a month it owes. The direct payment locks the guest
 * before reading its state, so a deactivation that has not committed yet is waited for: the payment
 * then sees the guest INACTIVE and refuses a month it does not owe.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
@Import(GuestDeactivationConcurrencyTest.FourteenthOfOctober.class)
class GuestDeactivationConcurrencyTest {

  /** 2026-10-14 in Lima: October is charged but due on the 20th, so it is not owed yet. */
  private static final Instant TODAY = Instant.parse("2026-10-14T15:00:00Z");

  private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
  private static final byte[] PDF = TestPdfs.plain();

  @TestConfiguration
  static class FourteenthOfOctober {
    @Bean
    @Primary
    Clock fourteenthOfOctober() {
      return new MutableClock(TODAY);
    }
  }

  @Autowired ManagePaymentsUseCase payments;
  @Autowired ManageGuestsUseCase guests;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transaction;
  @MockitoBean PaymentFilePort paymentFiles;

  private Long admin;
  private Long guest;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    when(paymentFiles.store(any(), any())).thenAnswer(call -> "vouchers/" + UUID.randomUUID());
    admin = TestIds.next();
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, 'admin@example.test', 'DNI', '00000001', 'hash', 'ADMIN', FALSE, 'ACTIVE')",
        admin);
    guest = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', '80000001', 'CONTRACT', 'ACTIVE')",
        guest);
    Long environment = TestIds.next();
    jdbc.update(
        "INSERT INTO environments (id, code, type, status) VALUES (?, '801', 'ROOM', 'ACTIVE')",
        environment);
    // Finished by hand today: it no longer blocks the deactivation and still charges October.
    jdbc.update(
        "INSERT INTO contracts (id, guest_id, environment_id, start_date, monthly_amount,"
            + " payment_day, grace_days, file_key, original_name, size_bytes, status,"
            + " terminated_at) VALUES (?, ?, ?, '2026-01-01', 800, 20, 3, 'contracts/a.pdf',"
            + " 'a.pdf', 10, 'TERMINATED', '2026-10-14T14:00:00Z')",
        TestIds.next(),
        guest,
        environment);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    TestTables.clear(jdbc);
  }

  /**
   * The deactivation has run and not committed when the direct payment of October arrives. Without
   * the lock the payment reads the guest as ACTIVE and approves a month an INACTIVE guest does not
   * owe.
   */
  @Test
  void aDirectPaymentNeverApprovesAMonthNotOwedByAGuestDeactivatedMeanwhile() throws Exception {
    var deactivatedNotCommitted = new CountDownLatch(1);
    var paymentDone = new CountDownLatch(1);
    CompletableFuture<Void> deactivation =
        CompletableFuture.runAsync(
            () ->
                asAdmin(
                    () -> {
                      transaction.executeWithoutResult(
                          status -> {
                            guests.deactivate(guest);
                            deactivatedNotCommitted.countDown();
                            // without the lock the payment ends here; with it, it waits for us
                            await(paymentDone, 3);
                          });
                      return null;
                    }));
    CompletableFuture<Boolean> refused =
        CompletableFuture.supplyAsync(
            () -> {
              await(deactivatedNotCommitted, 10);
              try {
                asAdmin(() -> payments.registerDirect(october()));
                return false;
              } catch (GuestNotActiveException e) {
                return true;
              } finally {
                paymentDone.countDown();
              }
            });

    deactivation.get(20, TimeUnit.SECONDS);
    assertThat(refused.get(20, TimeUnit.SECONDS)).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE status = 'APPROVED'", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT status FROM guests WHERE id = ?", String.class, guest))
        .isEqualTo("INACTIVE");
  }

  private DirectPayment october() {
    return new DirectPayment(
        admin,
        guest,
        OCTOBER,
        null,
        new BigDecimal("800"),
        "Efectivo",
        new VoucherUpload("v.pdf", PDF));
  }

  private <T> T asAdmin(Supplier<T> request) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(admin.toString(), "x", "ROLE_ADMIN"));
    try {
      return request.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static void await(CountDownLatch latch, long seconds) {
    try {
      latch.await(seconds, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
