package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.tarimatwasi.quipu.auth.application.port.in.LastActiveAdministratorException;
import com.tarimatwasi.quipu.auth.application.port.in.ManageAdministratorsUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.ManageAdministratorsUseCase.StatusChangeCommand;
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
import java.util.concurrent.CyclicBarrier;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * TAR-127: two active administrators who deactivate each other at the same time leave exactly one
 * active administrator, never none.
 *
 * <p>The two deactivations lock different rows (each its own target), so no row lock serializes
 * them: only the lock of the administrators does. The race is forced, not hoped for: both
 * re-authenticate before either looks up its target, and the first deactivation holds its
 * uncommitted change until the second is seen waiting in PostgreSQL for the advisory lock of the
 * backend that holds it; the test fails if that is never seen. Without the lock of the
 * administrators the second would neither wait nor see the first change, and both would pass.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc // Unused: the configuration of AdministratorsContractTest, to share its
// context.
@ImportTestcontainers(PostgresContainers.class)
class AdministratorDeactivationConcurrencyTest {

  private static final String PASSWORD = "Actor12345";

  @Autowired ManageAdministratorsUseCase administrators;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwordEncoder;
  @MockitoSpyBean AdministratorStorePort store;
  @MockitoBean AdministratorNoticePort notices;

  /** Unused here: the overrides of AdministratorsContractTest, to share its Spring context. */
  @MockitoBean PasswordResetMailPort mail;

  private final Long oneId = TestIds.next();
  private final Long otherId = TestIds.next();
  private ExecutorService pool;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    pool = Executors.newFixedThreadPool(2);
    String hash = passwordEncoder.encode(PASSWORD);
    insertAdmin(oneId, "una@example.test", "11111111", hash);
    insertAdmin(otherId, "otra@example.test", "11111112", hash);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    pool.shutdownNow();
    pool.awaitTermination(30, TimeUnit.SECONDS);
    TestTables.clear(jdbc);
  }

  private void insertAdmin(Long id, String email, String document, String hash) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status) VALUES (?, ?, 'DNI', ?, ?, 'ADMIN', FALSE, 'ACTIVE')",
        id,
        email,
        document,
        hash);
  }

  @Test
  void twoAdministratorsWhoDeactivateEachOtherAtOnceLeaveExactlyOneActive() throws Exception {
    AtomicBoolean waited = forceTheRace();

    CompletableFuture<Object> one =
        CompletableFuture.supplyAsync(() -> deactivate(oneId, otherId), pool);
    CompletableFuture<Object> two =
        CompletableFuture.supplyAsync(() -> deactivate(otherId, oneId), pool);
    List<Object> outcomes = new ArrayList<>();
    outcomes.add(one.get(30, TimeUnit.SECONDS));
    outcomes.add(two.get(30, TimeUnit.SECONDS));

    assertThat(waited).as("the second deactivation waited for the uncommitted first").isTrue();
    assertThat(outcomes).filteredOn("deactivated"::equals).hasSize(1);
    // The second one finds its own account deactivated (or, should that check go, no other active
    // administrator left): either way it changes nothing.
    assertThat(outcomes)
        .filteredOn(
            o ->
                o instanceof AccessDeniedException || o instanceof LastActiveAdministratorException)
        .hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE role = 'ADMIN' AND status = 'INACTIVE'",
                Integer.class))
        .isEqualTo(1);
  }

  /**
   * Both re-authenticate before either looks up its target, so the only thing left that can make
   * one wait for the other is the lock of the administrators; the first to change its target holds
   * the change until the other is seen waiting for that lock. Answers whether it was seen.
   */
  private AtomicBoolean forceTheRace() {
    var bothReauthenticated = new CyclicBarrier(2);
    doAnswer(
            call -> {
              bothReauthenticated.await(20, TimeUnit.SECONDS);
              return call.callRealMethod();
            })
        .when(store)
        .lockAdministrator(anyLong());
    var first = new AtomicBoolean(true);
    var waited = new AtomicBoolean();
    doAnswer(
            call -> {
              Object result = call.callRealMethod();
              if (first.getAndSet(false)) {
                int holder = backendPid();
                waited.set(waitUntil(() -> someoneWaitsForTheAdvisoryLockOf(holder)));
              }
              return result;
            })
        .when(store)
        .setAdministratorStatus(anyLong(), anyString(), anyLong());
    return waited;
  }

  /** As an administrator session of the actor; "deactivated", or the exception it ended in. */
  private Object deactivate(Long actor, Long target) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(actor.toString(), null, "ROLE_ADMIN"));
    try {
      administrators.deactivate(new StatusChangeCommand(actor, target, PASSWORD));
      return "deactivated";
    } catch (AccessDeniedException | LastActiveAdministratorException e) {
      return e;
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private int backendPid() {
    return Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
  }

  /** Someone waits on an advisory lock that the given backend holds. */
  private boolean someoneWaitsForTheAdvisoryLockOf(int holder) {
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
