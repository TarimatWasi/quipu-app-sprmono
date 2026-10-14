package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.Decision;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.DirectPayment;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.Resolution;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.VoucherUpload;
import com.tarimatwasi.quipu.payment.port.in.PaymentAlreadyExistsForPeriodException;
import com.tarimatwasi.quipu.payment.port.in.PaymentAlreadyResolvedException;
import com.tarimatwasi.quipu.payment.port.in.VoucherAlreadyExistsException;
import com.tarimatwasi.quipu.payment.port.out.PaymentFilePort;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Two requests at once, through the real service and the real database: the row lock and the unique
 * index decide, and the loser of a race leaves nothing behind in the bucket.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class PaymentConcurrencyTest {

  private static final int ROUNDS = 5;
  private static final YearMonth JANUARY = YearMonth.of(2026, 1);
  private static final byte[] PDF = "%PDF-1.7\n%race".getBytes(StandardCharsets.US_ASCII);

  @Autowired ManagePaymentsUseCase payments;
  @Autowired JdbcTemplate jdbc;
  @Autowired javax.sql.DataSource dataSource;
  @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
  @MockitoBean PaymentFilePort files;

  private Long admin;
  private Long guest;
  private Long contract;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    reset(files);
    when(files.store(any(), any())).thenAnswer(call -> "vouchers/" + UUID.randomUUID());
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
    Long room = TestIds.next();
    jdbc.update(
        "INSERT INTO environments (id, code, type, status) VALUES (?, '801', 'ROOM', 'ACTIVE')",
        room);
    contract = TestIds.next();
    jdbc.update(
        "INSERT INTO contracts (id, guest_id, environment_id, start_date, monthly_amount, payment_day,"
            + " grace_days, file_key, original_name, size_bytes) VALUES (?, ?, ?, '2026-01-01', 800, 5, 3,"
            + " 'contracts/a.pdf', 'a.pdf', 10)",
        contract,
        guest,
        room);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    TestTables.clear(jdbc);
  }

  private Long insertPending() {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO payments (id, guest_id, contract_id, period, amount, voucher_key, original_name,"
            + " content_type, size_bytes, status, recorded_by) VALUES (?, ?, ?, '2026-01-01', 800,"
            + " ?, 'v.pdf', 'application/pdf', 10, 'PENDING', 'GUEST')",
        id,
        guest,
        contract,
        "vouchers/" + id);
    return id;
  }

  private DirectPayment direct() {
    return new DirectPayment(
        admin, guest, JANUARY, null, new BigDecimal("800"), null, new VoucherUpload("v.pdf", PDF));
  }

  private int livePayments() {
    Integer live =
        jdbc.queryForObject(
            "SELECT count(*) FROM payments WHERE status IN ('PENDING', 'APPROVED')", Integer.class);
    return live == null ? 0 : live;
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

  private void approveNow(Long id) {
    payments.resolve(id, new Resolution(admin, Decision.APPROVED, null));
  }

  private void registerNow() {
    payments.registerDirect(direct());
  }

  private static long count(List<Optional<Throwable>> outcomes, Class<? extends Throwable> type) {
    return outcomes.stream().filter(o -> o.map(type::isInstance).orElse(false)).count();
  }

  private static long succeeded(List<Optional<Throwable>> outcomes) {
    return outcomes.stream().filter(Optional::isEmpty).count();
  }

  @Test
  void twoReviewersOfTheSameVoucherLeaveOneDecisionAndTheOtherSeesItResolved() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      Long id = insertPending();

      List<Optional<Throwable>> outcomes = race(() -> approveNow(id), () -> approveNow(id));

      assertThat(succeeded(outcomes)).as("approved once").isEqualTo(1);
      assertThat(count(outcomes, PaymentAlreadyResolvedException.class))
          .as("the other one")
          .isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, id))
          .isEqualTo("APPROVED");
      jdbc.update("DELETE FROM payments");
    }
  }

  @Test
  void approvingAVoucherWhileADirectPaymentIsRegisteredLeavesOnePaymentAndNoOrphanObject()
      throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      reset(files);
      when(files.store(any(), any())).thenAnswer(call -> "vouchers/" + UUID.randomUUID());
      Long id = insertPending();

      List<Optional<Throwable>> outcomes = race(() -> approveNow(id), this::registerNow);

      assertThat(outcomes.get(0)).as("the approval always goes through").isEmpty();
      assertThat(outcomes.get(1).orElseThrow())
          .as("the direct payment never finds the period free")
          .isInstanceOfAny(
              VoucherAlreadyExistsException.class, PaymentAlreadyExistsForPeriodException.class);
      assertThat(livePayments()).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT status FROM payments", String.class))
          .isEqualTo("APPROVED");
      // whatever was uploaded for the loser was removed
      var uploaded =
          org.mockito.Mockito.mockingDetails(files).getInvocations().stream()
              .filter(i -> i.getMethod().getName().equals("store"))
              .count();
      var removed =
          org.mockito.Mockito.mockingDetails(files).getInvocations().stream()
              .filter(i -> i.getMethod().getName().equals("delete"))
              .count();
      assertThat(removed).as("objects removed").isEqualTo(uploaded);
      jdbc.update("DELETE FROM payments");
    }
  }

  @Test
  void twoDirectPaymentsForOnePeriodRegisterOneAndTheOtherIsAConflict() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      reset(files);
      when(files.store(any(), any())).thenAnswer(call -> "vouchers/" + UUID.randomUUID());

      List<Optional<Throwable>> outcomes = race(this::registerNow, this::registerNow);

      assertThat(succeeded(outcomes)).as("registered once").isEqualTo(1);
      assertThat(count(outcomes, PaymentAlreadyExistsForPeriodException.class))
          .as("the loser gets a conflict, never a 500")
          .isEqualTo(1);
      assertThat(livePayments()).isEqualTo(1);
      verify(files, atLeast(1)).store(any(), any());
      var uploaded =
          org.mockito.Mockito.mockingDetails(files).getInvocations().stream()
              .filter(i -> i.getMethod().getName().equals("store"))
              .count();
      var removed =
          org.mockito.Mockito.mockingDetails(files).getInvocations().stream()
              .filter(i -> i.getMethod().getName().equals("delete"))
              .count();
      assertThat(uploaded - removed).as("only the stored voucher is left").isEqualTo(1);
      jdbc.update("DELETE FROM payments");
    }
  }

  @Test
  void aVoucherThatStaysPendingAndADirectPaymentThatLosesAreTold() {
    // not a race: the plain answer when the voucher is still pending
    Long id = insertPending();
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(admin.toString(), "x", "ROLE_ADMIN"));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> payments.registerDirect(direct()))
        .isInstanceOf(VoucherAlreadyExistsException.class);
    assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, id))
        .isEqualTo("PENDING");
  }

  /**
   * The voucher goes to the bucket before the row is committed: if the transaction does not commit,
   * whatever the reason, the object is removed.
   */
  @Test
  void aTransactionThatDoesNotCommitRemovesTheStoredVoucher() {
    var key = "vouchers/" + UUID.randomUUID();
    when(files.store(any(), any())).thenReturn(key);
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(admin.toString(), "x", "ROLE_ADMIN"));

    transaction.executeWithoutResult(
        status -> {
          payments.registerDirect(direct());
          status.setRollbackOnly();
        });

    verify(files).delete(key);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isZero();
  }

  @Test
  void aTransactionThatCommitsKeepsTheStoredVoucher() {
    var key = "vouchers/" + UUID.randomUUID();
    when(files.store(any(), any())).thenReturn(key);
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(admin.toString(), "x", "ROLE_ADMIN"));

    payments.registerDirect(direct());

    org.mockito.Mockito.verify(files, org.mockito.Mockito.never()).delete(any());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isEqualTo(1);
  }

  /** The conflict of the unique index reads the live payment on a second connection. */
  @Test
  void thePoolHasMoreThanOneConnection() {
    var pool = (com.zaxxer.hikari.HikariDataSource) dataSource;

    assertThat(pool.getMaximumPoolSize()).isGreaterThan(1);
  }
}
