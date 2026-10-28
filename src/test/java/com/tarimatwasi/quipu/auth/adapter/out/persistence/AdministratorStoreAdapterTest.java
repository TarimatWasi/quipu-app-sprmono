package com.tarimatwasi.quipu.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.port.out.AccountTakenException;
import com.tarimatwasi.quipu.auth.port.out.AccountTakenException.Taken;
import com.tarimatwasi.quipu.auth.port.out.AdministratorStorePort.NewAdministrator;
import com.tarimatwasi.quipu.auth.port.out.AdministratorStorePort.StoredAdministrator;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RF-22 against the real PostgreSQL: the unique rules of the accounts decide the conflict by their
 * name, and the lock of the administrators makes a second transaction wait for the first.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class AdministratorStoreAdapterTest {

  @Autowired AdministratorStoreAdapter adapter;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transaction;

  private final Long creatorId = TestIds.next();
  private ExecutorService pool;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    pool = Executors.newFixedThreadPool(2);
    insertUser(creatorId, "creador@example.test", "11111111", "ADMIN", "ACTIVE");
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    pool.shutdownNow();
    pool.awaitTermination(30, TimeUnit.SECONDS);
    TestTables.clear(jdbc);
  }

  private void insertUser(Long id, String email, String document, String role, String status) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status) VALUES (?, ?, 'DNI', ?, 'hash', ?, FALSE, ?)",
        id,
        email,
        document,
        role,
        status);
  }

  private NewAdministrator newAdmin(DocumentType type, String document, String email) {
    return new NewAdministrator(type, document, email, "unusable-hash", creatorId);
  }

  @Test
  void storesAnActiveAdministratorPendingItsPasswordWithItsCreator() {
    Long id = adapter.insert(newAdmin(DocumentType.DNI, "22222222", "nueva@example.test"));

    Map<String, @Nullable Object> row = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", id);
    assertThat(row)
        .containsEntry("role", "ADMIN")
        .containsEntry("status", "ACTIVE")
        .containsEntry("must_change_password", true)
        .containsEntry("created_by_id", creatorId)
        .containsEntry("password_hash", "unusable-hash")
        .containsEntry("document_type", "DNI")
        .containsEntry("document_number", "22222222")
        .containsEntry("email", "nueva@example.test");
    assertThat(row.get("guest_id")).isNull();
    assertThat(row.get("reset_token_hash")).isNull();
  }

  @Test
  void theDocumentOfAGuestAccountOrOfAnotherAdministratorIsTaken() {
    insertUser(TestIds.next(), "huesped@example.test", "33333333", "GUEST", "ACTIVE");

    assertThatThrownBy(
            () -> adapter.insert(newAdmin(DocumentType.DNI, "33333333", "x@example.test")))
        .isInstanceOfSatisfying(
            AccountTakenException.class, e -> assertThat(e.taken()).isEqualTo(Taken.DOCUMENT));
    assertThatThrownBy(
            () -> adapter.insert(newAdmin(DocumentType.DNI, "11111111", "y@example.test")))
        .isInstanceOfSatisfying(
            AccountTakenException.class, e -> assertThat(e.taken()).isEqualTo(Taken.DOCUMENT));
  }

  /** The same number under another type is another document (uq_users_document has both). */
  @Test
  void theSameNumberUnderAnotherTypeIsNotTaken() {
    assertThat(adapter.insert(newAdmin(DocumentType.CE, "11111111", "ce@example.test")))
        .isNotNull();
  }

  @Test
  void anEmailIsTakenIgnoringCase() {
    assertThatThrownBy(
            () -> adapter.insert(newAdmin(DocumentType.DNI, "22222222", "creador@example.test")))
        .isInstanceOfSatisfying(
            AccountTakenException.class, e -> assertThat(e.taken()).isEqualTo(Taken.EMAIL));
    assertThatThrownBy(
            () -> adapter.insert(newAdmin(DocumentType.DNI, "22222222", "CREADOR@Example.TEST")))
        .isInstanceOfSatisfying(
            AccountTakenException.class, e -> assertThat(e.taken()).isEqualTo(Taken.EMAIL));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
  }

  /** D-33: the contact email of a guest is not the email of an account. */
  @Test
  void theContactEmailOfAGuestIsNotTaken() {
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status, email)"
            + " VALUES (?, 'DNI', '44444444', 'CONTRACT', 'ACTIVE', 'contacto@example.test')",
        TestIds.next());

    assertThat(adapter.insert(newAdmin(DocumentType.DNI, "22222222", "Contacto@example.test")))
        .isNotNull();
  }

  /** Only the unique rules of the accounts are conflicts; any other failure is not hidden. */
  @Test
  void anotherFailureIsNotTakenForAConflict() {
    var unknownCreator =
        new NewAdministrator(
            DocumentType.DNI, "22222222", "z@example.test", "unusable-hash", TestIds.next());

    assertThatThrownBy(() -> adapter.insert(unknownCreator))
        .isInstanceOf(DataIntegrityViolationException.class)
        .isNotInstanceOf(AccountTakenException.class);
  }

  @Test
  void everyAdministratorCountsAndOnlyTheActiveOnesAreNotified() {
    Long active = TestIds.next();
    Long excluded = TestIds.next();
    insertUser(active, "activa@example.test", "22222222", "ADMIN", "ACTIVE");
    insertUser(excluded, "excluida@example.test", "22222223", "ADMIN", "ACTIVE");
    insertUser(TestIds.next(), "inactiva@example.test", "22222224", "ADMIN", "INACTIVE");
    insertUser(TestIds.next(), "huesped@example.test", "22222225", "GUEST", "ACTIVE");

    assertThat(adapter.countAdministrators()).isEqualTo(4);
    assertThat(adapter.activeAdministratorEmailsExcept(List.of(creatorId, excluded)))
        .containsExactly("activa@example.test");
    assertThat(adapter.activeAdministratorEmailsExcept(List.of()))
        .containsExactlyInAnyOrder(
            "creador@example.test", "activa@example.test", "excluida@example.test");
  }

  @Test
  void theLockNeedsATransaction() {
    assertThatThrownBy(adapter::lockAdministrators).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> adapter.holdAsReference(creatorId))
        .isInstanceOf(IllegalStateException.class);
  }

  /** TAR-127: every ADMIN, active or not, the oldest first; never a guest. */
  @Test
  void listsEveryAdministratorTheOldestFirst() {
    Long inactive = TestIds.next();
    Long older = TestIds.next();
    insertUser(inactive, "inactiva@example.test", "22222224", "ADMIN", "INACTIVE");
    insertUser(older, "antigua@example.test", "AB12", "ADMIN", "ACTIVE");
    insertUser(TestIds.next(), "huesped@example.test", "22222225", "GUEST", "ACTIVE");
    jdbc.update("UPDATE users SET document_type = 'PASSPORT' WHERE id = ?", older);
    setCreated(older, "2026-01-01T10:00:00Z");
    setCreated(creatorId, "2026-02-01T10:00:00Z");
    setCreated(inactive, "2026-03-01T10:00:00Z");

    assertThat(adapter.listAdministrators())
        .containsExactly(
            new StoredAdministrator(
                older,
                DocumentType.PASSPORT,
                "AB12",
                "antigua@example.test",
                "ACTIVE",
                Instant.parse("2026-01-01T10:00:00Z")),
            new StoredAdministrator(
                creatorId,
                DocumentType.DNI,
                "11111111",
                "creador@example.test",
                "ACTIVE",
                Instant.parse("2026-02-01T10:00:00Z")),
            new StoredAdministrator(
                inactive,
                DocumentType.DNI,
                "22222224",
                "inactiva@example.test",
                "INACTIVE",
                Instant.parse("2026-03-01T10:00:00Z")));
  }

  @Test
  void onlyTheActiveAdministratorsCountAsActive() {
    insertUser(TestIds.next(), "activa@example.test", "22222222", "ADMIN", "ACTIVE");
    insertUser(TestIds.next(), "inactiva@example.test", "22222224", "ADMIN", "INACTIVE");
    insertUser(TestIds.next(), "huesped@example.test", "22222225", "GUEST", "ACTIVE");

    assertThat(adapter.countActiveAdministrators()).isEqualTo(2);
    assertThat(adapter.countAdministrators()).isEqualTo(3);
  }

  /** Only an ADMIN is found, and only inside a transaction (its row stays locked). */
  @Test
  void anAdministratorIsLockedByItsIdAndAGuestIsNotFound() {
    Long guest = TestIds.next();
    insertUser(guest, "huesped@example.test", "22222225", "GUEST", "ACTIVE");

    assertThatThrownBy(() -> adapter.lockAdministrator(creatorId))
        .isInstanceOf(IllegalStateException.class);
    transaction.executeWithoutResult(
        status -> {
          assertThat(adapter.lockAdministrator(creatorId))
              .hasValueSatisfying(
                  a -> {
                    assertThat(a.id()).isEqualTo(creatorId);
                    assertThat(a.email()).isEqualTo("creador@example.test");
                    assertThat(a.isActive()).isTrue();
                  });
          assertThat(adapter.lockAdministrator(guest)).isEmpty();
          assertThat(adapter.lockAdministrator(TestIds.next())).isEmpty();
        });
  }

  @Test
  void theStatusOfAnAdministratorChangesWithWhoChangedIt() {
    Long other = TestIds.next();
    insertUser(other, "otra@example.test", "22222222", "ADMIN", "ACTIVE");
    jdbc.update("UPDATE users SET last_modified_date = '2026-01-01T00:00:00Z' WHERE id = ?", other);

    adapter.setAdministratorStatus(other, "INACTIVE", creatorId);

    Map<String, @Nullable Object> row =
        jdbc.queryForMap(
            "SELECT status, last_modified_by_id, last_modified_date > '2026-01-01T00:00:00Z'"
                + " AS touched FROM users WHERE id = ?",
            other);
    assertThat(row)
        .containsEntry("status", "INACTIVE")
        .containsEntry("last_modified_by_id", creatorId)
        .containsEntry("touched", true);
    assertThat(
            jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, creatorId))
        .isEqualTo("ACTIVE");

    adapter.setAdministratorStatus(other, "ACTIVE", creatorId);
    assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, other))
        .isEqualTo("ACTIVE");
  }

  @Test
  void theStatusOfAGuestAccountIsNeverChangedHere() {
    Long guest = TestIds.next();
    insertUser(guest, "huesped@example.test", "22222225", "GUEST", "ACTIVE");

    assertThatThrownBy(() -> adapter.setAdministratorStatus(guest, "INACTIVE", creatorId))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, guest))
        .isEqualTo("ACTIVE");
  }

  private void setCreated(Long id, String instant) {
    jdbc.update("UPDATE users SET created_date = ?::timestamptz WHERE id = ?", instant, id);
  }

  /**
   * The second transaction is seen waiting in PostgreSQL on the backend of the first until the
   * first ends; the test fails if it never waits.
   */
  @Test
  void aSecondTransactionWaitsForTheLockOfTheFirst() throws Exception {
    var locked = new CountDownLatch(1);
    var secondDone = new CountDownLatch(1);
    var secondWaited = new AtomicBoolean();
    CompletableFuture<Void> first =
        CompletableFuture.runAsync(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      adapter.lockAdministrators();
                      int holder = backendPid();
                      locked.countDown();
                      boolean seen =
                          waitUntil(() -> secondDone.getCount() == 0 || someoneWaitsOn(holder));
                      secondWaited.set(seen && secondDone.getCount() != 0);
                    }),
            pool);
    assertThat(locked.await(20, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<Void> second =
        CompletableFuture.runAsync(
            () -> {
              try {
                transaction.executeWithoutResult(status -> adapter.lockAdministrators());
              } finally {
                secondDone.countDown();
              }
            },
            pool);

    first.get(20, TimeUnit.SECONDS);
    second.get(20, TimeUnit.SECONDS);

    assertThat(secondWaited).as("the second transaction waited for the first").isTrue();
  }

  private int backendPid() {
    return Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
  }

  /** Someone waits on an advisory lock that the given backend holds. */
  private boolean someoneWaitsOn(int holder) {
    Integer waiting =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_activity a"
                + " WHERE a.wait_event_type = 'Lock' AND a.wait_event = 'advisory'"
                + " AND a.datname = current_database() AND ? = ANY (pg_blocking_pids(a.pid))",
            Integer.class,
            holder);
    return waiting != null && waiting > 0;
  }

  private static boolean waitUntil(BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
      if (condition.getAsBoolean()) {
        return true;
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
    }
    return false;
  }
}
