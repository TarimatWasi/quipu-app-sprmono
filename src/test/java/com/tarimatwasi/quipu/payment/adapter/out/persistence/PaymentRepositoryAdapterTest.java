package com.tarimatwasi.quipu.payment.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.payment.port.out.LivePaymentExistsException;
import com.tarimatwasi.quipu.payment.port.out.PaymentRepositoryPort.DirectDraft;
import com.tarimatwasi.quipu.payment.port.out.PaymentRepositoryPort.StoredPayment;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.CursorToken;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** TAR-43: the payments, their queue and the unique indexes that decide who gets a period. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class PaymentRepositoryAdapterTest {

  private static final Instant REVIEWED = Instant.parse("2026-10-14T15:00:00Z");
  private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

  @Autowired PaymentRepositoryAdapter adapter;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  private Long admin;
  private Long guest;
  private Long temporaryGuest;
  private Long contract;
  private Long stay;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    admin = TestIds.next();
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, 'admin@example.test', 'DNI', '00000001', 'hash', 'ADMIN', FALSE, 'ACTIVE')",
        admin);
    guest = insertGuest("70000001", "CONTRACT");
    temporaryGuest = insertGuest("70000002", "TEMPORARY");
    Long room = TestIds.next();
    jdbc.update(
        "INSERT INTO environments (id, code, type, status) VALUES (?, '701', 'ROOM', 'ACTIVE')",
        room);
    contract = TestIds.next();
    jdbc.update(
        "INSERT INTO contracts (id, guest_id, environment_id, start_date, monthly_amount, payment_day,"
            + " grace_days, file_key, original_name, size_bytes) VALUES (?, ?, ?, '2026-01-01', 800, 5, 3,"
            + " 'contracts/a.pdf', 'a.pdf', 10)",
        contract,
        guest,
        room);
    stay = TestIds.next();
    jdbc.update(
        "INSERT INTO stays (id, guest_id, start_date, end_date, agreed_amount)"
            + " VALUES (?, ?, '2026-10-01', '2026-10-10', 150)",
        stay,
        temporaryGuest);
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  private Long insertGuest(String number, String type) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', ?, ?, 'ACTIVE')",
        id,
        number,
        type);
    return id;
  }

  private DirectDraft draftForPeriod(String month) {
    return new DirectDraft(
        guest,
        contract,
        null,
        YearMonth.parse(month),
        new BigDecimal("800.50"),
        "Yape",
        "vouchers/" + TestIds.next(),
        "v.pdf",
        VoucherType.PDF,
        1234,
        REVIEWED,
        admin);
  }

  private DirectDraft draftForStay() {
    return new DirectDraft(
        temporaryGuest,
        null,
        stay,
        null,
        new BigDecimal("150"),
        null,
        "vouchers/" + TestIds.next(),
        "v.png",
        VoucherType.PNG,
        99,
        REVIEWED,
        admin);
  }

  /** A voucher a guest uploaded, which the next PRs create through their own port. */
  private Long insertPending(String month, @Nullable String createdDate) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO payments (id, guest_id, contract_id, period, amount, voucher_key, original_name,"
            + " content_type, size_bytes, status, recorded_by, created_date) VALUES (?, ?, ?,"
            + " ?::date, 800, ?, 'v.jpg', 'image/jpeg', 10, 'PENDING', 'GUEST',"
            + " COALESCE(?::timestamptz, now()))",
        id,
        guest,
        contract,
        month + "-01",
        "vouchers/" + id,
        createdDate);
    return id;
  }

  @Test
  void insertApproved_storesTheVoucherOfAPeriodApprovedByTheAdmin() {
    StoredPayment stored = adapter.insertApproved(draftForPeriod("2026-10"));

    assertThat(stored.payment().status()).isEqualTo(PaymentStatus.APPROVED);
    assertThat(stored.payment().period()).isEqualTo(OCTOBER);
    assertThat(stored.payment().contractId()).isEqualTo(contract);
    assertThat(stored.payment().stayId()).isNull();
    assertThat(stored.payment().amount()).isEqualByComparingTo("800.50");
    assertThat(stored.payment().reviewedById()).isEqualTo(admin);
    assertThat(stored.payment().reviewedAt()).isEqualTo(REVIEWED);
    assertThat(stored.voucherType()).isEqualTo(VoucherType.PDF);
    assertThat(stored.createdAt()).isNotNull();
    assertThat(adapter.findById(stored.payment().id()).orElseThrow().payment())
        .isEqualTo(stored.payment());
    assertThat(
            jdbc.queryForObject(
                "SELECT recorded_by FROM payments WHERE id = ?",
                String.class,
                stored.payment().id()))
        .isEqualTo("ADMIN");
  }

  @Test
  void insertApproved_storesTheVoucherOfAStay() {
    StoredPayment stored = adapter.insertApproved(draftForStay());

    assertThat(stored.payment().stayId()).isEqualTo(stay);
    assertThat(stored.payment().contractId()).isNull();
    assertThat(stored.payment().period()).isNull();
    assertThat(stored.voucherType()).isEqualTo(VoucherType.PNG);
  }

  /**
   * Inside a transaction the failed insert leaves it unusable, so the answer is read in another
   * one: each case below runs in a transaction of its own, as a request does.
   */
  @Test
  void insertApproved_tellsWhichPaymentIsAlreadyThere() {
    insertPending("2026-10", null);
    adapter.insertApproved(draftForPeriod("2026-11"));

    // As in a request, the exception leaves the transaction and rolls it back.
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status -> adapter.insertApproved(draftForPeriod("2026-10"))))
        .isInstanceOfSatisfying(
            LivePaymentExistsException.class,
            e -> assertThat(e.liveStatus()).isEqualTo(PaymentStatus.PENDING));
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status -> adapter.insertApproved(draftForPeriod("2026-11"))))
        .isInstanceOfSatisfying(
            LivePaymentExistsException.class,
            e -> assertThat(e.liveStatus()).isEqualTo(PaymentStatus.APPROVED));
  }

  @Test
  void insertApproved_aStayWithALivePaymentIsRefusedWithItsStatus() {
    adapter.insertApproved(draftForStay());

    assertThatThrownBy(() -> adapter.insertApproved(draftForStay()))
        .isInstanceOfSatisfying(
            LivePaymentExistsException.class,
            e -> assertThat(e.liveStatus()).isEqualTo(PaymentStatus.APPROVED));
  }

  @Test
  void insertApproved_anotherIntegrityViolationIsNotMistakenForALivePayment() {
    var unknownGuest =
        new DirectDraft(
            -9L,
            contract,
            null,
            OCTOBER,
            BigDecimal.TEN,
            null,
            "vouchers/x",
            "v.pdf",
            VoucherType.PDF,
            1,
            REVIEWED,
            admin);

    assertThatThrownBy(() -> adapter.insertApproved(unknownGuest))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void findLive_seesThePendingAndTheApprovedAndNeverTheRejected() {
    assertThat(adapter.findLiveByPeriod(contract, OCTOBER)).isEmpty();
    Long rejected = insertPending("2026-10", null);
    jdbc.update(
        "UPDATE payments SET status = 'REJECTED', rejection_reason = 'No coincide',"
            + " reviewed_at = now(), reviewed_by_id = ? WHERE id = ?",
        admin,
        rejected);
    assertThat(adapter.findLiveByPeriod(contract, OCTOBER)).isEmpty();

    insertPending("2026-10", null);
    assertThat(adapter.findLiveByPeriod(contract, OCTOBER))
        .get()
        .extracting(p -> p.status())
        .isEqualTo(PaymentStatus.PENDING);
    assertThat(adapter.findLiveByPeriod(contract, YearMonth.of(2026, 11))).isEmpty();

    adapter.insertApproved(draftForStay());
    assertThat(adapter.findLiveByStay(stay)).isPresent();
    assertThat(adapter.findLiveByStay(-1L)).isEmpty();
    assertThat(adapter.findAllByContract(contract)).hasSize(2);
  }

  @Test
  void save_storesTheResolution() {
    Long id = insertPending("2026-10", null);
    StoredPayment pending = adapter.findById(id).orElseThrow();

    StoredPayment saved = adapter.save(pending.payment().reject(admin, "Monto menor", REVIEWED));

    assertThat(saved.payment().status()).isEqualTo(PaymentStatus.REJECTED);
    assertThat(saved.payment().rejectionReason()).isEqualTo("Monto menor");
    assertThat(adapter.findById(id).orElseThrow().payment().reviewedById()).isEqualTo(admin);
  }

  @Test
  void findByIdForUpdate_locksTheRowUntilTheTransactionEnds() {
    Long id = insertPending("2026-10", null);
    String lockProbe = "SELECT id FROM payments WHERE id = ? FOR UPDATE NOWAIT";

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              assertThat(adapter.findByIdForUpdate(id)).isPresent();
              assertThatThrownBy(
                      () ->
                          CompletableFuture.runAsync(() -> jdbc.queryForList(lockProbe, id)).join())
                  .hasCauseInstanceOf(UncategorizedSQLException.class);
            });

    assertThat(jdbc.queryForList(lockProbe, id)).hasSize(1);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> assertThat(adapter.findByIdForUpdate(-1L)).isEmpty());
  }

  @Test
  void findQueue_walksTheOldestFirstWithoutRepeatingOrSkipping() {
    List<Long> created = new ArrayList<>();
    for (int month = 1; month <= 5; month++) {
      created.add(insertPending("2026-0" + month, "2026-10-0" + month + "T10:00:00Z"));
    }
    List<Long> seen = new ArrayList<>();
    String cursor = null;
    do {
      CursorPage<StoredPayment> page = adapter.findQueue(null, new PageQuery(cursor, 2));
      page.content().forEach(p -> seen.add(p.payment().id()));
      cursor = page.nextCursor();
    } while (cursor != null);

    assertThat(seen).containsExactlyElementsOf(created);
  }

  @Test
  void findQueue_filtersByStatusAndKeepsTheIdAsTheTieBreaker() {
    Long first = insertPending("2026-01", "2026-10-01T10:00:00Z");
    Long second = insertPending("2026-02", "2026-10-01T10:00:00Z");
    adapter.insertApproved(draftForStay());

    assertThat(adapter.findQueue(PaymentStatus.PENDING, PageQuery.first()).content())
        .extracting(p -> p.payment().id())
        .containsExactly(first, second);
    assertThat(adapter.findQueue(PaymentStatus.APPROVED, PageQuery.first()).content()).hasSize(1);
    assertThat(adapter.findQueue(PaymentStatus.REJECTED, PageQuery.first()).content()).isEmpty();
  }

  @Test
  void findQueue_refusesACursorThatIsNotOurs() {
    for (String bad :
        new String[] {
          "nope",
          CursorToken.encode("not-a-date", "1"),
          CursorToken.encode("+12345-01-01T00:00:00", "1"),
          CursorToken.encode("2026-01-01T00:00:00", "x")
        }) {
      assertThatThrownBy(() -> adapter.findQueue(null, new PageQuery(bad, 5)))
          .as(bad)
          .isInstanceOf(InvalidCursorException.class);
    }
  }

  @Test
  void theQueueWithoutAStatusHasAnIndexForItsOrder() {
    assertThat(
            jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_payments_created'",
                String.class))
        .contains("(created_date, id)");
  }
}
