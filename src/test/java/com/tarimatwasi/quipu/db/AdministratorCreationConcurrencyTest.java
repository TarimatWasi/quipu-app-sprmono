package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.application.port.in.AdministratorDocumentTakenException;
import com.tarimatwasi.quipu.auth.application.port.in.AdministratorEmailTakenException;
import com.tarimatwasi.quipu.auth.application.port.in.AdministratorLimitReachedException;
import com.tarimatwasi.quipu.auth.application.port.in.CreateAdministratorUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.CreateAdministratorUseCase.CreateAdministratorCommand;
import com.tarimatwasi.quipu.auth.application.port.in.LoginUseCase.DocumentKind;
import com.tarimatwasi.quipu.auth.application.port.out.AdministratorNoticePort;
import com.tarimatwasi.quipu.auth.application.port.out.AdministratorStorePort;
import com.tarimatwasi.quipu.auth.application.port.out.PasswordResetMailPort;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * RF-22: two simultaneous creations end in as many administrators as the maximum allows and, for
 * the same document or email, in a single account with the right conflict for the other.
 *
 * <p>The two creations come from two administrators, so the row lock of a creator does not
 * serialize them: only the lock of the administrators does.
 *
 * <p>Each race is forced, not hoped for: the first creation holds its uncommitted account until the
 * second is seen waiting in PostgreSQL on the backend that holds it, and the test fails if that is
 * never seen. Without the lock of the administrators the second would count the committed rows
 * only, never wait, and pass the maximum.
 */
@SpringBootTest(
    properties = {"app.cors.allowed-origin=http://localhost:3000", "app.administrators.max=4"})
@ImportTestcontainers(PostgresContainers.class)
class AdministratorCreationConcurrencyTest {

  private static final String PASSWORD = "Creador12345";

  @Autowired CreateAdministratorUseCase administrators;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwordEncoder;
  @MockitoSpyBean AdministratorStorePort store;
  @MockitoBean PasswordResetMailPort mail;
  @MockitoBean AdministratorNoticePort notices;

  private final Long creatorId = TestIds.next();
  private final Long otherCreatorId = TestIds.next();
  private ExecutorService pool;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    pool = Executors.newFixedThreadPool(2);
    insertAdmin(creatorId, "creador@example.test", "11111111");
    insertAdmin(otherCreatorId, "otro.creador@example.test", "11111113");
    when(mail.sendAccessLinkNow(anyString(), anyString(), any())).thenReturn(true);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    pool.shutdownNow();
    pool.awaitTermination(30, TimeUnit.SECONDS);
    TestTables.clear(jdbc);
  }

  private void insertAdmin(Long id, String email, String document) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status) VALUES (?, ?, 'DNI', ?, ?, 'ADMIN', FALSE, 'ACTIVE')",
        id,
        email,
        document,
        passwordEncoder.encode(PASSWORD));
  }

  @Test
  void twoCreationsAtOnceDoNotPassTheMaximum() throws Exception {
    insertAdmin(TestIds.next(), "otra@example.test", "11111112");

    List<Object> outcomes = race("22222222", "a@example.test", "33333333", "b@example.test");

    assertThat(outcomes)
        .filteredOn(AdministratorLimitReachedException.class::isInstance)
        .hasSize(1);
    assertThat(outcomes).filteredOn(Long.class::isInstance).hasSize(1);
    assertThat(administratorCount()).isEqualTo(4);
  }

  @Test
  void twoCreationsWithTheSameEmailLeaveOneAccountAndAnEmailConflict() throws Exception {
    List<Object> outcomes =
        race("22222222", "misma@example.test", "33333333", "MISMA@example.test");

    assertThat(outcomes).filteredOn(AdministratorEmailTakenException.class::isInstance).hasSize(1);
    assertThat(outcomes).filteredOn(Long.class::isInstance).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE lower(email) = 'misma@example.test'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void twoCreationsWithTheSameDocumentLeaveOneAccountAndADocumentConflict() throws Exception {
    List<Object> outcomes = race("22222222", "a@example.test", "22222222", "b@example.test");

    assertThat(outcomes)
        .filteredOn(AdministratorDocumentTakenException.class::isInstance)
        .hasSize(1);
    assertThat(outcomes).filteredOn(Long.class::isInstance).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE document_number = '22222222'", Integer.class))
        .isEqualTo(1);
  }

  /**
   * The first creation to insert holds its row until the other is seen waiting on its backend;
   * returns the id or the exception of each creation.
   */
  private List<Object> race(String document1, String email1, String document2, String email2)
      throws Exception {
    var first = new AtomicBoolean(true);
    var waited = new AtomicBoolean();
    doAnswer(
            call -> {
              Object id = call.callRealMethod();
              if (first.getAndSet(false)) {
                int holder = backendPid();
                waited.set(waitUntil(() -> someoneWaitsOn(holder)));
              }
              return id;
            })
        .when(store)
        .insert(any());
    CompletableFuture<Object> one =
        CompletableFuture.supplyAsync(() -> create(creatorId, document1, email1), pool);
    CompletableFuture<Object> two =
        CompletableFuture.supplyAsync(() -> create(otherCreatorId, document2, email2), pool);

    List<Object> outcomes = new ArrayList<>();
    outcomes.add(one.get(30, TimeUnit.SECONDS));
    outcomes.add(two.get(30, TimeUnit.SECONDS));
    assertThat(waited).as("the second creation waited for the uncommitted first").isTrue();
    return outcomes;
  }

  /** As an administrator session of the creator; the id, or the exception it ended in. */
  private Object create(Long creator, String document, String email) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(creator.toString(), null, "ROLE_ADMIN"));
    try {
      return administrators
          .create(
              new CreateAdministratorCommand(creator, DocumentKind.DNI, document, email, PASSWORD))
          .id();
    } catch (AdministratorLimitReachedException
        | AdministratorEmailTakenException
        | AdministratorDocumentTakenException e) {
      return e;
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private int administratorCount() {
    return Objects.requireNonNull(
        jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN'", Integer.class));
  }

  private int backendPid() {
    return Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
  }

  /** Someone waits on a lock that the given backend holds. */
  private boolean someoneWaitsOn(int holder) {
    Integer waiting =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_activity a"
                + " WHERE a.wait_event_type = 'Lock' AND a.datname = current_database()"
                + " AND ? = ANY (pg_blocking_pids(a.pid))",
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
