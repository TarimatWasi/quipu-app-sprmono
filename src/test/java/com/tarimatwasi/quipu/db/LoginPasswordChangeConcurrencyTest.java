package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.tarimatwasi.quipu.auth.application.port.in.ChangePasswordUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.ChangePasswordUseCase.ChangePasswordCommand;
import com.tarimatwasi.quipu.auth.application.port.in.InvalidCredentialsException;
import com.tarimatwasi.quipu.auth.application.port.in.LoginUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.LoginUseCase.DocumentKind;
import com.tarimatwasi.quipu.auth.application.port.in.LoginUseCase.LoginCommand;
import com.tarimatwasi.quipu.auth.application.port.in.PasswordRecoveryUseCase;
import com.tarimatwasi.quipu.auth.application.port.out.SessionTokenPort;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.AccountJwtAuthenticationConverter;
import com.tarimatwasi.quipu.shared.hashing.Sha256;
import com.tarimatwasi.quipu.support.LockWaits;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.SessionIds;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TAR-215: a login with the old password that races a password change or a reset never leaves a
 * session that outlives the change. The three take the row lock of the account before deciding: a
 * login that waits for the change reads the new hash and is refused, and a change that waits for
 * the login takes its cutoff after the token, which then stops being valid.
 *
 * <p>Each race is forced, not hoped for: the first transaction holds the row until the other one is
 * seen waiting in PostgreSQL on the backend that holds it, and the test fails if that is never
 * seen.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class LoginPasswordChangeConcurrencyTest {

  private static final String OLD_PASSWORD = "Antigua12345";
  private static final String NEW_PASSWORD = "Nueva12345";
  private static final String RESET_CODE = "codigo-de-prueba-tar215";

  @Autowired LoginUseCase logins;
  @Autowired ChangePasswordUseCase passwords;
  @Autowired PasswordRecoveryUseCase recovery;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transaction;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JwtDecoder decoder;
  @Autowired AccountJwtAuthenticationConverter sessions;
  @MockitoSpyBean SessionTokenPort sessionTokens;

  private Long user;

  /**
   * Its own per test: the login limiter counts every attempt per document and lives as long as the
   * cached context, so a document shared by the tests would run into it.
   */
  private String document;

  /** Its own per test, so a transaction left hanging by a failed test is stopped before cleanup. */
  private ExecutorService pool;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    pool = Executors.newFixedThreadPool(2);
    user = TestIds.next();
    document = TestIds.next().toString();
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status, reset_token_hash, reset_token_expires_at)"
            + " VALUES (?, 'admin@example.test', 'DNI', ?, ?, 'ADMIN', FALSE, 'ACTIVE', ?,"
            + " now() + interval '30 minutes')",
        user,
        document,
        passwordEncoder.encode(OLD_PASSWORD),
        sha256(RESET_CODE));
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    pool.shutdownNow();
    pool.awaitTermination(30, TimeUnit.SECONDS);
    TestTables.clear(jdbc);
  }

  @Test
  void aLoginArrivingDuringAPasswordChangeIsRefused() throws Exception {
    assertLoginDuringIsRefused(this::changePassword);
  }

  @Test
  void aLoginArrivingDuringAResetIsRefused() throws Exception {
    assertLoginDuringIsRefused(this::resetPassword);
  }

  @Test
  void aPasswordChangeArrivingDuringALoginEndsItsSession() throws Exception {
    assertChangeDuringLoginEndsTheSession(this::changePassword);
  }

  @Test
  void aResetArrivingDuringALoginEndsItsSession() throws Exception {
    assertChangeDuringLoginEndsTheSession(this::resetPassword);
  }

  /**
   * The change has run and holds the row uncommitted when the login arrives. Without the lock of
   * the login, it reads the committed old hash and gets a token issued after the cutoff.
   */
  private void assertLoginDuringIsRefused(Runnable change) throws Exception {
    var changedNotCommitted = new CountDownLatch(1);
    var loginDone = new CountDownLatch(1);
    var loginWaited = new AtomicBoolean();
    CompletableFuture<Void> changing =
        CompletableFuture.runAsync(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      change.run();
                      int holder = LockWaits.backendPid(jdbc);
                      changedNotCommitted.countDown();
                      boolean seen =
                          LockWaits.until(
                              () -> loginDone.getCount() == 0 || blocksOnTheAccounts(holder));
                      loginWaited.set(seen && loginDone.getCount() != 0);
                    }),
            pool);
    assertThat(changedNotCommitted.await(20, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<@Nullable String> login =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return loginWith(OLD_PASSWORD);
              } catch (InvalidCredentialsException e) {
                return null;
              } finally {
                loginDone.countDown();
              }
            },
            pool);

    changing.get(20, TimeUnit.SECONDS);
    String token = login.get(20, TimeUnit.SECONDS);

    assertThat(token).as("a session opened with the old password").isNull();
    assertThat(loginWaited).as("the login waited for the uncommitted change").isTrue();
    assertThat(isValid(loginWith(NEW_PASSWORD))).isTrue();
  }

  /**
   * The login has read the account and is about to issue its token when the change arrives. Without
   * the lock of the change, its cutoff is taken before the token is issued, so the token survives.
   */
  private void assertChangeDuringLoginEndsTheSession(Runnable change) throws Exception {
    var issuing = new CountDownLatch(1);
    var first = new AtomicBoolean(true);
    var holder = new AtomicInteger();
    doAnswer(
            call -> {
              if (first.getAndSet(false)) {
                holder.set(LockWaits.backendPid(jdbc));
                issuing.countDown();
                if (!LockWaits.until(() -> blocksOnTheAccounts(holder.get()))) {
                  throw new IllegalStateException("The change never waited for the login");
                }
              }
              return call.callRealMethod();
            })
        .when(sessionTokens)
        // TAR-228: the login opens its stored session and signs its token with renew.
        .renew(anyString(), anyString());
    CompletableFuture<String> login =
        CompletableFuture.supplyAsync(() -> loginWith(OLD_PASSWORD), pool);
    assertThat(issuing.await(20, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<Void> changing = CompletableFuture.runAsync(change, pool);

    String token = login.get(20, TimeUnit.SECONDS);
    changing.get(20, TimeUnit.SECONDS);

    assertThat(isValid(token)).as("the session opened with the old password").isFalse();
    assertThat(isValid(loginWith(NEW_PASSWORD))).isTrue();
  }

  private void changePassword() {
    passwords.changePassword(
        new ChangePasswordCommand(
            user.toString(), SessionIds.newSessionId(), OLD_PASSWORD, NEW_PASSWORD));
  }

  private void resetPassword() {
    recovery.resetPassword(RESET_CODE, NEW_PASSWORD, "Ana Quispe Mamani");
  }

  private String loginWith(String password) {
    return logins
        .login(new LoginCommand(DocumentKind.DNI, document, password))
        .session()
        .accessToken();
  }

  /** What the security chain does with the cookie on every request. */
  private boolean isValid(String token) {
    try {
      sessions.convert(decoder.decode(token));
      return true;
    } catch (JwtException | AuthenticationException e) {
      return false;
    }
  }

  private static String sha256(String code) {
    return Sha256.hex(code);
  }

  /** Someone waits for the given backend while it holds a lock on the table of the accounts. */
  private boolean blocksOnTheAccounts(int holder) {
    return LockWaits.someoneWaitsOn(jdbc, holder) && LockWaits.holdsALockOn(jdbc, holder, "users");
  }
}
