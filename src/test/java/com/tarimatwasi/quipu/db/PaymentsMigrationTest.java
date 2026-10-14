package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TAR-43 (V13): the payments table over existing data, its checks and its partial unique indexes.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class PaymentsMigrationTest {

  private static final AtomicLong NEXT_ID = new AtomicLong(90_000);

  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;

  private long admin;
  private long guest;
  private long otherGuest;
  private long contract;
  private long stay;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    admin = TestIds.next();
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, 'admin@example.test', 'DNI', '00000001', 'hash', 'ADMIN', FALSE, 'ACTIVE')",
        admin);
    guest = insertGuest("public", "CONTRACT", "60000001");
    otherGuest = insertGuest("public", "CONTRACT", "60000002");
    long room = insertEnvironment("public", "601");
    contract = insertContract("public", guest, room);
    long temporary = insertGuest("public", "TEMPORARY", "60000003");
    stay = insertStay("public", temporary);
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  /** The table is added over guests, contracts and stays that already exist, and touches none. */
  @Test
  void v13_addsAnEmptyTableOverExistingData() {
    String schema = "tar43_migration";
    jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    try {
      migrateTo(schema, "12");
      long g = insertGuest(schema, "CONTRACT", "60000010");
      long e = insertEnvironment(schema, "611");
      long c = insertContract(schema, g, e);

      migrateTo(schema, "13");

      assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".payments", Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject("SELECT count(*) FROM " + schema + ".contracts", Integer.class))
          .isEqualTo(1);
      jdbc.update(
          "INSERT INTO "
              + schema
              + ".payments (id, guest_id, contract_id, period, amount, voucher_key, original_name,"
              + " content_type, size_bytes, recorded_by) VALUES (nextval('"
              + schema
              + ".payments_seq'), ?, ?, '2026-10-01', 800, 'vouchers/a', 'a.pdf', 'application/pdf',"
              + " 10, 'GUEST')",
          g,
          c);
      assertThat(jdbc.queryForObject("SELECT status FROM " + schema + ".payments", String.class))
          .isEqualTo("PENDING");
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  /** The contracts that were finished before V13 get the instant of their last modification. */
  @Test
  void v13_backfillsWhenAFinishedContractWasFinished() {
    String schema = "tar43_backfill";
    jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    try {
      migrateTo(schema, "12");
      long g = insertGuest(schema, "CONTRACT", "60000020");
      long e1 = insertEnvironment(schema, "621");
      long e2 = insertEnvironment(schema, "622");
      long active = insertContract(schema, g, e1);
      long finished = insertContract(schema, g, e2);
      jdbc.update(
          "UPDATE "
              + schema
              + ".contracts SET status = 'TERMINATED', last_modified_date = '2026-09-15T10:00:00Z'"
              + " WHERE id = ?",
          finished);

      migrateTo(schema, "13");

      assertThat(
              jdbc.queryForObject(
                  "SELECT terminated_at IS NULL FROM " + schema + ".contracts WHERE id = ?",
                  Boolean.class,
                  active))
          .isTrue();
      assertThat(
              jdbc.queryForObject(
                  "SELECT terminated_at = last_modified_date FROM "
                      + schema
                      + ".contracts WHERE id = ?",
                  Boolean.class,
                  finished))
          .isTrue();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void aContractIsFinishedExactlyWhenItHasTheInstantItWasFinished() {
    assertThatThrownBy(
            () -> jdbc.update("UPDATE contracts SET status = 'TERMINATED' WHERE id = ?", contract))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE contracts SET terminated_at = now() WHERE id = ?", contract))
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.update(
        "UPDATE contracts SET status = 'TERMINATED', terminated_at = now() WHERE id = ?", contract);
  }

  @Test
  void payments_idComesFromASequenceThatGrowsBy50() {
    assertThat(
            jdbc.queryForObject(
                "SELECT increment_by FROM pg_sequences WHERE sequencename = 'payments_seq'",
                Long.class))
        .isEqualTo(50L);
  }

  @Test
  void payments_haveTheIndexesOfTheDebtTheQueueAndTheHistory() {
    List<String> definitions =
        jdbc.queryForList(
            "SELECT pg_get_indexdef(indexrelid) FROM pg_index WHERE indrelid = 'payments'::regclass",
            String.class);

    assertThat(definitions)
        .anyMatch(
            d ->
                d.contains("UNIQUE")
                    && d.contains("(guest_id, contract_id, period)")
                    && d.contains("'PENDING'"))
        .anyMatch(
            d ->
                d.contains("UNIQUE")
                    && d.contains("(contract_id, period)")
                    && d.contains("'APPROVED'"))
        .anyMatch(
            d ->
                d.contains("UNIQUE")
                    && d.contains("(stay_id)")
                    && d.contains("'PENDING'")
                    && d.contains("'APPROVED'"))
        .anyMatch(d -> d.contains("(guest_id, status, period)"))
        .anyMatch(d -> d.contains("(status, created_date, id)"))
        .anyMatch(d -> d.contains("(guest_id, created_date DESC, id DESC)"));
  }

  // --- the checks of the table ---

  @Test
  void aPaymentBelongsToExactlyOneOfAContractPeriodOrAStay() {
    assertRefused("both", contract, "2026-10-01", stay, "PENDING", null, false);
    assertRefused("none", null, null, null, "PENDING", null, false);
    assertRefused("a contract without period", contract, null, null, "PENDING", null, false);
    assertRefused("a period without contract", null, "2026-10-01", stay, "PENDING", null, false);
    insert(guest, contract, "2026-10-01", null, "PENDING", null, false);
    insert(guest, null, null, stay, "PENDING", null, false);
  }

  @Test
  void aPeriodIsTheFirstDayOfAMonth() {
    assertRefused("mid month", contract, "2026-10-15", null, "PENDING", null, false);
    insert(guest, contract, "2026-10-01", null, "PENDING", null, false);
  }

  @Test
  void aRejectionNeedsAReasonThatIsNotBlank() {
    assertRefused("no reason", contract, "2026-10-01", null, "REJECTED", null, true);
    assertRefused("blank reason", contract, "2026-10-01", null, "REJECTED", "   ", true);
    insert(guest, contract, "2026-10-01", null, "REJECTED", "Voucher ilegible", true);
  }

  @Test
  void aResolvedPaymentSaysWhoAndWhenAndAPendingOneDoesNot() {
    assertRefused("approved unreviewed", contract, "2026-10-01", null, "APPROVED", null, false);
    assertRefused("pending reviewed", contract, "2026-10-01", null, "PENDING", null, true);
    insert(guest, contract, "2026-10-01", null, "APPROVED", null, true);
  }

  @Test
  void theColumnsRefuseWhatTheDomainRefuses() {
    assertThatThrownBy(() -> insertRaw("amount", "0"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertRaw("size_bytes", "0"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertRaw("status", "'EXPIRED'"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertRaw("recorded_by", "'ROBOT'"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // --- the partial unique indexes ---

  @Test
  void aRejectedVoucherDoesNotBlockANewOne() {
    insert(guest, contract, "2026-10-01", null, "REJECTED", "Ilegible", true);
    insert(guest, contract, "2026-10-01", null, "REJECTED", "Otra vez ilegible", true);
    insert(guest, contract, "2026-10-01", null, "PENDING", null, false);
    insert(guest, contract, "2026-10-01", null, "APPROVED", null, true);
  }

  @Test
  void onlyOneVoucherCanBePendingForAPeriodAndOnlyOneApproved() {
    insert(guest, contract, "2026-10-01", null, "PENDING", null, false);
    assertThatThrownBy(() -> insert(guest, contract, "2026-10-01", null, "PENDING", null, false))
        .isInstanceOf(DataIntegrityViolationException.class);
    insert(guest, contract, "2026-11-01", null, "PENDING", null, false);
    insert(guest, contract, "2026-10-01", null, "APPROVED", null, true);
    assertThatThrownBy(() -> insert(guest, contract, "2026-10-01", null, "APPROVED", null, true))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aStayHasOneLivePaymentAtATime() {
    long first = insert(guest, null, null, stay, "PENDING", null, false);
    assertThatThrownBy(() -> insert(guest, null, null, stay, "PENDING", null, false))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insert(guest, null, null, stay, "APPROVED", null, true))
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.update(
        "UPDATE payments SET status = 'REJECTED', rejection_reason = 'No coincide',"
            + " reviewed_at = now(), reviewed_by_id = ? WHERE id = ?",
        admin,
        first);
    insert(guest, null, null, stay, "PENDING", null, false);
  }

  @Test
  void twoSimultaneousVouchersForOnePeriodLeaveExactlyOne() throws Exception {
    assertOneWinner(
        () -> insert(guest, contract, "2026-10-01", null, "PENDING", null, false),
        () -> insert(guest, contract, "2026-10-01", null, "PENDING", null, false));
  }

  @Test
  void twoSimultaneousApprovalsOfOnePeriodLeaveExactlyOne() throws Exception {
    assertOneWinner(
        () -> insert(guest, contract, "2026-10-01", null, "APPROVED", null, true),
        () -> insert(otherGuest, contract, "2026-10-01", null, "APPROVED", null, true));
  }

  @Test
  void twoSimultaneousPaymentsOfOneStayLeaveExactlyOne() throws Exception {
    assertOneWinner(
        () -> insert(guest, null, null, stay, "PENDING", null, false),
        () -> insert(otherGuest, null, null, stay, "APPROVED", null, true));
  }

  /** Both inserts start together, on two connections: the database lets exactly one in. */
  private void assertOneWinner(Runnable first, Runnable second) throws Exception {
    var barrier = new CyclicBarrier(2);
    var a = CompletableFuture.supplyAsync(() -> attempt(barrier, first));
    var b = CompletableFuture.supplyAsync(() -> attempt(barrier, second));

    assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isEqualTo(1);
  }

  private static boolean attempt(CyclicBarrier barrier, Runnable insert) {
    try {
      barrier.await();
      insert.run();
      return true;
    } catch (DataIntegrityViolationException refused) {
      return false;
    } catch (InterruptedException | java.util.concurrent.BrokenBarrierException broken) {
      throw new IllegalStateException(broken);
    }
  }

  // --- helpers ---

  private void assertRefused(
      String why,
      @Nullable Long contractId,
      @Nullable String period,
      @Nullable Long stayId,
      String status,
      @Nullable String reason,
      boolean reviewed) {
    assertThatThrownBy(() -> insert(guest, contractId, period, stayId, status, reason, reviewed))
        .as(why)
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private long insert(
      long guestId,
      @Nullable Long contractId,
      @Nullable String period,
      @Nullable Long stayId,
      String status,
      @Nullable String reason,
      boolean reviewed) {
    long id = NEXT_ID.incrementAndGet();
    jdbc.update(
        "INSERT INTO payments (id, guest_id, contract_id, stay_id, period, amount, voucher_key,"
            + " original_name, content_type, size_bytes, status, rejection_reason, reviewed_at,"
            + " reviewed_by_id, recorded_by) VALUES (?, ?, ?, ?, ?::date, 800, 'vouchers/a', 'a.pdf',"
            + " 'application/pdf', 10, ?, ?, CASE WHEN ? THEN now() END, CASE WHEN ? THEN ?::bigint END,"
            + " 'GUEST')",
        id,
        guestId,
        contractId,
        stayId,
        period,
        status,
        reason,
        reviewed,
        reviewed,
        admin);
    return id;
  }

  /** A valid pending payment with one column replaced by a value the table must refuse. */
  private void insertRaw(String column, String value) {
    jdbc.execute(
        "INSERT INTO payments (id, guest_id, contract_id, period, amount, voucher_key, original_name,"
            + " content_type, size_bytes, status, recorded_by) SELECT 1, "
            + guest
            + ", "
            + contract
            + ", '2026-12-01'::date, "
            + ("amount".equals(column) ? value : "800")
            + ", 'vouchers/a', 'a.pdf', 'application/pdf', "
            + ("size_bytes".equals(column) ? value : "10")
            + ", "
            + ("status".equals(column) ? value : "'PENDING'")
            + ", "
            + ("recorded_by".equals(column) ? value : "'GUEST'"));
  }

  private void migrateTo(String schema, String version) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .createSchemas(true)
        .target(version)
        .load()
        .migrate();
  }

  private long insertGuest(String schema, String type, String number) {
    long id = NEXT_ID.incrementAndGet();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', ?, ?, 'ACTIVE')",
        id,
        number,
        type);
    return id;
  }

  private long insertEnvironment(String schema, String code) {
    long id = NEXT_ID.incrementAndGet();
    jdbc.update(
        "INSERT INTO " + schema + ".environments (id, code, type) VALUES (?, ?, 'ROOM')", id, code);
    return id;
  }

  private long insertContract(String schema, long guestId, long environmentId) {
    long id = NEXT_ID.incrementAndGet();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".contracts (id, guest_id, environment_id, start_date, monthly_amount, payment_day,"
            + " grace_days, file_key, original_name, size_bytes)"
            + " VALUES (?, ?, ?, '2026-01-01', 800, 5, 3, 'contracts/a.pdf', 'a.pdf', 10)",
        id,
        guestId,
        environmentId);
    return id;
  }

  private long insertStay(String schema, long guestId) {
    long id = NEXT_ID.incrementAndGet();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".stays (id, guest_id, start_date, end_date, agreed_amount)"
            + " VALUES (?, ?, '2026-10-01', '2026-10-10', 150)",
        id,
        guestId);
    return id;
  }
}
