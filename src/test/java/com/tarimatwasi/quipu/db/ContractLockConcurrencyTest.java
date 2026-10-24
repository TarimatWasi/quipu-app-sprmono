package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.contract.port.in.ManageContractsUseCase;
import com.tarimatwasi.quipu.contract.port.in.ManageContractsUseCase.ContractFile;
import com.tarimatwasi.quipu.contract.port.in.ManageContractsUseCase.NewContract;
import com.tarimatwasi.quipu.contract.port.out.ContractFilePort;
import com.tarimatwasi.quipu.payment.port.in.InvalidPaymentException;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.DirectPayment;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.VoucherUpload;
import com.tarimatwasi.quipu.payment.port.out.PaymentFilePort;
import com.tarimatwasi.quipu.shared.debt.GuestHasPendingDebtException;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
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
 * TAR-192: the row locks of the contract and of the guest, through the real services and the real
 * database. Lock order: environment, guest, contract, payment.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
@Import(ContractLockConcurrencyTest.LastSecondOfOctober.class)
class ContractLockConcurrencyTest {

  /** 2026-10-31 23:59:59 in Lima: the next month is two seconds away. */
  private static final Instant LAST_SECOND_OF_OCTOBER = Instant.parse("2026-11-01T04:59:59Z");

  private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
  private static final byte[] PDF = TestPdfs.plain();
  private static final int ROUNDS = 5;

  @TestConfiguration
  static class LastSecondOfOctober {
    @Bean
    @Primary
    Clock lastSecondOfOctober() {
      return new MutableClock(LAST_SECOND_OF_OCTOBER);
    }
  }

  @Autowired ManagePaymentsUseCase payments;
  @Autowired ManageContractsUseCase contracts;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transaction;
  @Autowired Clock clock;
  @MockitoBean PaymentFilePort paymentFiles;
  @MockitoBean ContractFilePort contractFiles;

  private Long admin;
  private Long guest;
  private Long contract;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    MutableClock mutable = (MutableClock) clock;
    mutable.advance(Duration.between(mutable.instant(), LAST_SECOND_OF_OCTOBER));
    when(paymentFiles.store(any(), any())).thenAnswer(call -> "vouchers/" + UUID.randomUUID());
    when(contractFiles.store(any())).thenAnswer(call -> "contracts/" + UUID.randomUUID());
    when(contractFiles.signedUrl(any())).thenReturn("https://r2.test/contract");
    admin = TestIds.next();
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, 'admin@example.test', 'DNI', '00000001', 'hash', 'ADMIN', FALSE, 'ACTIVE')",
        admin);
    guest = insertGuest("80000001");
    contract = insertContract(guest, insertEnvironment("801"));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    TestTables.clear(jdbc);
  }

  private Long insertGuest(String document) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', ?, 'CONTRACT', 'ACTIVE')",
        id,
        document);
    return id;
  }

  private Long insertEnvironment(String code) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO environments (id, code, type, status) VALUES (?, ?, 'ROOM', 'ACTIVE')",
        id,
        code);
    return id;
  }

  /** Open-ended from January and never paid: from February on, the guest is in debt. */
  private Long insertContract(Long guestId, Long environmentId) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO contracts (id, guest_id, environment_id, start_date, monthly_amount, payment_day,"
            + " grace_days, file_key, original_name, size_bytes) VALUES (?, ?, ?, '2026-01-01', 800, 5, 3,"
            + " 'contracts/a.pdf', 'a.pdf', 10)",
        id,
        guestId,
        environmentId);
    return id;
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

  private int approvedAfterTheTermination() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM payments p JOIN contracts c ON c.id = p.contract_id"
                + " WHERE p.status = 'APPROVED' AND c.terminated_at IS NOT NULL"
                + " AND p.period > date_trunc('month', c.terminated_at AT TIME ZONE 'America/Lima')",
            Integer.class);
    return count == null ? 0 : count;
  }

  /**
   * The termination takes its instant in the last second of October and has not committed yet when
   * midnight passes and the direct payment of November arrives. Without the lock the payment reads
   * the contract as not terminated and is approved for a month the contract never charges.
   */
  @Test
  void aDirectPaymentOfTheNextMonthNeverOutlivesATerminationThatRacesIt() throws Exception {
    var terminatedNotCommitted = new CountDownLatch(1);
    var paymentDone = new CountDownLatch(1);
    CompletableFuture<Void> termination =
        CompletableFuture.runAsync(
            () ->
                asAdmin(
                    () -> {
                      transaction.executeWithoutResult(
                          status -> {
                            contracts.terminate(contract);
                            terminatedNotCommitted.countDown();
                            // without the lock the payment ends here; with it, it waits for us
                            await(paymentDone, 3);
                          });
                      return null;
                    }));
    CompletableFuture<Optional<Throwable>> payment =
        CompletableFuture.runAsync(() -> payNovemberAfterMidnight(terminatedNotCommitted))
            .whenComplete((done, failure) -> paymentDone.countDown())
            .handle(
                (done, failure) ->
                    Optional.ofNullable(failure)
                        .map(f -> Objects.requireNonNullElse(f.getCause(), f)));

    termination.get(30, TimeUnit.SECONDS);
    Optional<Throwable> outcome = payment.get(30, TimeUnit.SECONDS);

    assertThat(approvedAfterTheTermination()).as("approved after the termination").isZero();
    assertThat(outcome)
        .as("the payment reads the terminated contract under the lock")
        .get()
        .isInstanceOfSatisfying(
            InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("period"));
  }

  private void payNovemberAfterMidnight(CountDownLatch terminatedNotCommitted) {
    asAdmin(
        () -> {
          await(terminatedNotCommitted, 10);
          ((MutableClock) clock).advance(Duration.ofSeconds(2));
          return payments.registerDirect(
              new DirectPayment(
                  admin,
                  guest,
                  NOVEMBER,
                  null,
                  new BigDecimal("800"),
                  null,
                  new VoucherUpload("v.pdf", PDF)));
        });
  }

  private NewContract newContract(Long guestId, Long environmentId) {
    return new NewContract(
        environmentId,
        guestId,
        new BigDecimal("800"),
        5,
        3,
        LocalDate.of(2026, 11, 1),
        null,
        new ContractFile("c.pdf", PDF));
  }

  /** What each of two requests that start together ends with: nothing, or the exception it got. */
  private List<Optional<Throwable>> race(Runnable first, Runnable second) {
    var barrier = new CyclicBarrier(2);
    var a = start(barrier, first);
    var b = start(barrier, second);
    return List.of(a.join(), b.join());
  }

  private CompletableFuture<Optional<Throwable>> start(CyclicBarrier barrier, Runnable request) {
    return CompletableFuture.runAsync(
            () ->
                asAdmin(
                    () -> {
                      try {
                        barrier.await();
                      } catch (InterruptedException | BrokenBarrierException broken) {
                        throw new IllegalStateException(broken);
                      }
                      request.run();
                      return null;
                    }))
        .handle(
            (done, failure) ->
                Optional.ofNullable(failure).map(f -> Objects.requireNonNullElse(f.getCause(), f)));
  }

  @Test
  void twoContractsOfAGuestInDebtOnTwoEnvironmentsAtOnceAreBothRefused() {
    Long second = insertEnvironment("802");
    Long third = insertEnvironment("803");
    for (int round = 0; round < ROUNDS; round++) {
      List<Optional<Throwable>> outcomes =
          race(
              () -> contracts.create(newContract(guest, second)),
              () -> contracts.create(newContract(guest, third)));

      assertThat(outcomes)
          .allSatisfy(o -> assertThat(o).get().isInstanceOf(GuestHasPendingDebtException.class));
      assertThat(jdbc.queryForObject("SELECT count(*) FROM contracts", Integer.class)).isEqualTo(1);
    }
  }

  @Test
  void twoContractsOfAGuestWithoutDebtOnTwoEnvironmentsAtOnceAreBothStoredAsBefore() {
    Long newcomer = insertGuest("80000002");
    for (int round = 0; round < ROUNDS; round++) {
      Long second = insertEnvironment("9" + round + "2");
      Long third = insertEnvironment("9" + round + "3");

      List<Optional<Throwable>> outcomes =
          race(
              () -> contracts.create(newContract(newcomer, second)),
              () -> contracts.create(newContract(newcomer, third)));

      assertThat(outcomes).allSatisfy(o -> assertThat(o).isEmpty());
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM contracts WHERE guest_id = ?", Integer.class, newcomer))
          .isEqualTo(2 * (round + 1));
    }
  }
}
