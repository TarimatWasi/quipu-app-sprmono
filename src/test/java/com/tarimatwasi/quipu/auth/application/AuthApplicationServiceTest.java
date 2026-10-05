package com.tarimatwasi.quipu.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.in.AccountLockedException;
import com.tarimatwasi.quipu.auth.port.in.ChangePasswordUseCase.ChangePasswordCommand;
import com.tarimatwasi.quipu.auth.port.in.ChangePasswordUseCase.ChangePasswordResult;
import com.tarimatwasi.quipu.auth.port.in.CurrentSessionUseCase.CurrentSession;
import com.tarimatwasi.quipu.auth.port.in.LoginUseCase.LoginCommand;
import com.tarimatwasi.quipu.auth.port.in.LoginUseCase.LoginResult;
import com.tarimatwasi.quipu.auth.port.in.NoActiveSessionException;
import com.tarimatwasi.quipu.auth.port.in.PasswordUnchangedException;
import com.tarimatwasi.quipu.auth.port.in.WeakPasswordException;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthApplicationServiceTest {

  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-05T10:00:00Z"));
  private InMemoryUserRepository repository;
  private AuthApplicationService service;

  @BeforeEach
  void setUp() {
    repository = new InMemoryUserRepository();
    service =
        new AuthApplicationService(
            repository,
            encoder,
            (userId, role, mustChange) -> userId + ":" + role + ":" + mustChange,
            clock);
  }

  @Test
  void logsInWithCorrectDocumentAndPassword() {
    UserAccount admin =
        new UserAccount(
            UUID.randomUUID(),
            "admin@tarimatwasi.local",
            DocumentType.DNI,
            "00000000",
            encoder.encode("Temporal123!"),
            Role.ADMIN,
            null,
            true,
            "ACTIVE",
            0,
            null,
            null);
    repository.save(admin);

    LoginResult result =
        service.login(new LoginCommand(DocumentType.DNI, "00000000", "Temporal123!"));

    assertThat(result.role()).isEqualTo(Role.ADMIN);
    assertThat(result.mustChangePassword()).isTrue();
  }

  @Test
  void rejectsWrongPasswordWithGenericError() {
    UserAccount admin =
        new UserAccount(
            UUID.randomUUID(),
            "admin@tarimatwasi.local",
            DocumentType.DNI,
            "00000000",
            encoder.encode("Temporal123!"),
            Role.ADMIN,
            null,
            true,
            "ACTIVE",
            0,
            null,
            null);
    repository.save(admin);

    assertThatThrownBy(() -> service.login(new LoginCommand(DocumentType.DNI, "00000000", "wrong")))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  @Test
  void rejectsUnknownDocumentWithSameGenericError() {
    assertThatThrownBy(
            () -> service.login(new LoginCommand(DocumentType.DNI, "99999999", "whatever")))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  private UserAccount savedUser(String rawPassword, boolean mustChangePassword, String status) {
    UserAccount user =
        new UserAccount(
            UUID.randomUUID(),
            "user@tarimatwasi.local",
            DocumentType.DNI,
            "11111111",
            encoder.encode(rawPassword),
            Role.GUEST,
            null,
            mustChangePassword,
            status,
            0,
            null,
            null);
    repository.save(user);
    return user;
  }

  private ChangePasswordCommand change(UserAccount user, @Nullable String current, String next) {
    return new ChangePasswordCommand(user.id().toString(), current, next);
  }

  @Test
  void changesTheTemporaryPasswordWithoutTheCurrentOne() {
    UserAccount user = savedUser("Temporal123!", true, "ACTIVE");

    ChangePasswordResult result = service.changePassword(change(user, null, "Nueva12345"));

    assertThat(result.sessionToken()).isEqualTo(user.id() + ":GUEST:false");
    UserAccount stored = repository.find(user.id());
    assertThat(encoder.matches("Nueva12345", stored.passwordHash())).isTrue();
    assertThat(stored.mustChangePassword()).isFalse();
  }

  @Test
  void acceptsExactlyEightCharacters() {
    UserAccount user = savedUser("Temporal123!", true, "ACTIVE");

    service.changePassword(change(user, null, "12345678"));

    assertThat(encoder.matches("12345678", repository.find(user.id()).passwordHash())).isTrue();
  }

  @Test
  void rejectsAPasswordShorterThanEightCharacters() {
    UserAccount user = savedUser("Temporal123!", true, "ACTIVE");

    assertThatThrownBy(() -> service.changePassword(change(user, null, "1234567")))
        .isInstanceOfSatisfying(
            WeakPasswordException.class,
            e -> assertThat(e.reason()).isEqualTo(WeakPasswordException.Reason.TOO_SHORT));
    assertThat(repository.find(user.id()).mustChangePassword()).isTrue();
  }

  /** Four emoji are eight UTF-16 units but only four characters. */
  @Test
  void countsCharactersNotUtf16UnitsForTheMinimum() {
    UserAccount user = savedUser("Temporal123!", true, "ACTIVE");
    String fourEmoji = "😀".repeat(4);

    assertThatThrownBy(() -> service.changePassword(change(user, null, fourEmoji)))
        .isInstanceOfSatisfying(
            WeakPasswordException.class,
            e -> assertThat(e.reason()).isEqualTo(WeakPasswordException.Reason.TOO_SHORT));
  }

  @Test
  void rejectsAPasswordLongerThanBcryptCanHash() {
    UserAccount user = savedUser("Temporal123!", true, "ACTIVE");
    String tooLong = "ñ".repeat(37); // 74 bytes in UTF-8

    assertThatThrownBy(() -> service.changePassword(change(user, null, tooLong)))
        .isInstanceOfSatisfying(
            WeakPasswordException.class,
            e -> assertThat(e.reason()).isEqualTo(WeakPasswordException.Reason.TOO_LONG));
  }

  @Test
  void rejectsKeepingTheTemporaryPassword() {
    UserAccount user = savedUser("Temporal123!", true, "ACTIVE");

    assertThatThrownBy(() -> service.changePassword(change(user, null, "Temporal123!")))
        .isInstanceOf(PasswordUnchangedException.class);
    assertThat(repository.find(user.id()).mustChangePassword()).isTrue();
  }

  @Test
  void requiresTheCurrentPasswordOutsideTheForcedFlow() {
    UserAccount user = savedUser("Actual12345", false, "ACTIVE");

    assertThatThrownBy(() -> service.changePassword(change(user, null, "Nueva12345")))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  @Test
  void rejectsAWrongCurrentPasswordEvenInTheForcedFlow() {
    UserAccount user = savedUser("Temporal123!", true, "ACTIVE");

    assertThatThrownBy(() -> service.changePassword(change(user, "equivocada", "Nueva12345")))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThat(repository.find(user.id()).mustChangePassword()).isTrue();
  }

  @Test
  void changesThePasswordWithTheRightCurrentOneOutsideTheForcedFlow() {
    UserAccount user = savedUser("Actual12345", false, "ACTIVE");

    service.changePassword(change(user, "Actual12345", "Nueva12345"));

    assertThat(encoder.matches("Nueva12345", repository.find(user.id()).passwordHash())).isTrue();
  }

  @Test
  void rejectsAnUnknownAccount() {
    var command = new ChangePasswordCommand(UUID.randomUUID().toString(), null, "Nueva12345");

    assertThatThrownBy(() -> service.changePassword(command))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  @Test
  void rejectsAMalformedUserId() {
    var command = new ChangePasswordCommand("no-es-un-uuid", null, "Nueva12345");

    assertThatThrownBy(() -> service.changePassword(command))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  @Test
  void rejectsADisabledAccount() {
    UserAccount user = savedUser("Temporal123!", true, "INACTIVE");

    assertThatThrownBy(() -> service.changePassword(change(user, null, "Nueva12345")))
        .isInstanceOf(AccountDisabledException.class);
  }

  /**
   * TAR-74: the session shown to the client is the account as it is now, not the token's claims.
   */
  @Test
  void currentSessionReadsTheAccountAsItIsNow() {
    UserAccount user = savedUser("Temporal123!", true, "ACTIVE");

    CurrentSession session = service.currentSession(user.id().toString());

    assertThat(session.role()).isEqualTo("GUEST");
    assertThat(session.displayEmail()).isEqualTo("user@tarimatwasi.local");
    assertThat(session.mustChangePassword()).isTrue();
  }

  @Test
  void currentSessionOfADisabledUnknownOrMalformedAccountIsNoSession() {
    String disabled = savedUser("Temporal123!", false, "INACTIVE").id().toString();
    String unknown = UUID.randomUUID().toString();

    assertThatThrownBy(() -> service.currentSession(disabled))
        .isInstanceOf(NoActiveSessionException.class);
    assertThatThrownBy(() -> service.currentSession(unknown))
        .isInstanceOf(NoActiveSessionException.class);
    assertThatThrownBy(() -> service.currentSession("no-es-un-uuid"))
        .isInstanceOf(NoActiveSessionException.class);
  }

  /** SEG-06 (TAR-99): 5 consecutive failed logins lock the account for 15 minutes. */
  private void failLogins(int times) {
    for (int i = 0; i < times; i++) {
      assertThatThrownBy(() -> service.login(loginOf("Mala-clave-1")))
          .isInstanceOf(InvalidCredentialsException.class);
    }
  }

  private LoginCommand loginOf(String password) {
    return new LoginCommand(DocumentType.DNI, "11111111", password);
  }

  @Test
  void fiveConsecutiveFailuresLockTheAccountEvenForTheRightPassword() {
    savedUser("Correcta-123", false, "ACTIVE");

    failLogins(5);

    assertThatThrownBy(() -> service.login(loginOf("Correcta-123")))
        .isInstanceOf(AccountLockedException.class);
  }

  @Test
  void fourFailuresDoNotLockAndASuccessfulLoginRestartsTheCount() {
    savedUser("Correcta-123", false, "ACTIVE");

    failLogins(4);
    assertThat(service.login(loginOf("Correcta-123")).role()).isEqualTo(Role.GUEST);
    failLogins(4);

    assertThat(service.login(loginOf("Correcta-123")).role()).isEqualTo(Role.GUEST);
  }

  @Test
  void theLockLastsFifteenMinutesAndIsNotExtendedByAttemptsMadeWhileLocked() {
    savedUser("Correcta-123", false, "ACTIVE");
    failLogins(5);

    clock.advance(Duration.ofMinutes(10));
    assertThatThrownBy(() -> service.login(loginOf("Mala-clave-1")))
        .isInstanceOf(AccountLockedException.class);
    clock.advance(Duration.ofMinutes(5).minusSeconds(1));
    assertThatThrownBy(() -> service.login(loginOf("Correcta-123")))
        .isInstanceOf(AccountLockedException.class);

    clock.advance(Duration.ofSeconds(1));
    assertThat(service.login(loginOf("Correcta-123")).role()).isEqualTo(Role.GUEST);
  }

  @Test
  void aFailureAfterTheLockExpiredStartsANewCountInsteadOfLockingAgain() {
    savedUser("Correcta-123", false, "ACTIVE");
    failLogins(5);
    clock.advance(Duration.ofMinutes(15));

    failLogins(4);

    assertThat(service.login(loginOf("Correcta-123")).role()).isEqualTo(Role.GUEST);
  }

  /** The lock is decided under the row lock: a burst that read the account before it locked. */
  @Test
  void aFailureThatFindsTheAccountAlreadyLockedAnswersLocked() {
    UserAccount unlocked =
        new UserAccount(
            UUID.randomUUID(),
            "user@tarimatwasi.local",
            DocumentType.DNI,
            "11111111",
            encoder.encode("Correcta-123"),
            Role.GUEST,
            null,
            false,
            "ACTIVE",
            4,
            null,
            null);
    UserRepositoryPort stale = mock(UserRepositoryPort.class);
    when(stale.findByDocumentForUpdate(DocumentType.DNI, "11111111"))
        .thenReturn(Optional.of(unlocked));
    when(stale.registerFailedLogin(eq(unlocked.id()), any(), anyInt(), any())).thenReturn(true);
    var racing = new AuthApplicationService(stale, encoder, (u, r, m) -> "t", clock);

    assertThatThrownBy(() -> racing.login(loginOf("Mala-clave-1")))
        .isInstanceOf(AccountLockedException.class);
  }

  /** The whole login decision is serialized per account: the row is locked before anything else. */
  @Test
  void theLoginReadsTheAccountUnderTheRowLock() {
    UserRepositoryPort repo = mock(UserRepositoryPort.class);
    when(repo.findByDocumentForUpdate(DocumentType.DNI, "11111111")).thenReturn(Optional.empty());
    var locking = new AuthApplicationService(repo, encoder, (u, r, m) -> "t", clock);

    assertThatThrownBy(() -> locking.login(loginOf("Correcta-123")))
        .isInstanceOf(InvalidCredentialsException.class);

    verify(repo).findByDocumentForUpdate(DocumentType.DNI, "11111111");
    verify(repo, never()).findByDocument(any(), any());
  }

  /** TAR-125: a change of password and a login of the same account are decided one by one. */
  @Test
  void changingThePasswordReadsTheAccountUnderTheRowLock() {
    UserAccount user = savedUser("Actual12345", false, "ACTIVE");
    UserRepositoryPort spied = org.mockito.Mockito.spy(repository);
    var locking = new AuthApplicationService(spied, encoder, (u, r, m) -> "t", clock);

    locking.changePassword(change(user, "Actual12345", "Nueva12345"));

    verify(spied).findByIdForUpdate(user.id());
    verify(spied, never()).findById(any());
  }

  @Test
  void anUnknownDocumentIsNeverLocked() {
    for (int i = 0; i < 8; i++) {
      assertThatThrownBy(() -> service.login(loginOf("Mala-clave-1")))
          .isInstanceOf(InvalidCredentialsException.class);
    }
  }
}
