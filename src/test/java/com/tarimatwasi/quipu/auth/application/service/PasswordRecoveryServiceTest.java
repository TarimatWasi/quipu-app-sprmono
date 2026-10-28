package com.tarimatwasi.quipu.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tarimatwasi.quipu.auth.application.port.in.InvalidResetCodeException;
import com.tarimatwasi.quipu.auth.application.port.in.WeakPasswordException;
import com.tarimatwasi.quipu.auth.application.port.out.AccessGrantRepositoryPort;
import com.tarimatwasi.quipu.auth.application.port.out.MailDeliveryException;
import com.tarimatwasi.quipu.auth.application.port.out.PasswordResetMailPort;
import com.tarimatwasi.quipu.auth.application.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.TestIds;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** RF-16: the recovery code is single-use, expires, and the answer never reveals the account. */
class PasswordRecoveryServiceTest {

  private static final String EMAIL = "guest@example.test";
  private static final String NEW_PASSWORD = "Nueva12345";

  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-03T12:00:00Z"));
  private final RecordingMail mail = new RecordingMail();
  private final AccessGrantRepositoryPort grants = mock(AccessGrantRepositoryPort.class);
  private InMemoryUserRepository repository;
  private PasswordRecoveryService service;

  @BeforeEach
  void setUp() {
    repository = new InMemoryUserRepository();
    service =
        new PasswordRecoveryService(
            new ResetCodeIssuer(repository, clock),
            repository,
            encoder,
            mail,
            Runnable::run,
            grants,
            clock);
  }

  private UserAccount savedUser(String status) {
    var user =
        new UserAccount(
            TestIds.next(),
            EMAIL,
            DocumentType.DNI,
            "11111111",
            encoder.encode("Antigua12345"),
            Role.GUEST,
            null,
            true,
            status,
            0,
            null,
            null);
    repository.save(user);
    return user;
  }

  /** TAR-205: stored as TIMESTAMPTZ keeps it, so no rounding of the database can move it. */
  @Test
  void theResetIsMarkedToTheMicrosecond() {
    UserAccount user = savedUser("ACTIVE");
    service.requestReset(EMAIL);
    clock.advance(Duration.ofNanos(1_999));

    service.resetPassword(mail.sent.getFirst().code(), NEW_PASSWORD);

    assertThat(repository.find(user.id()).passwordChangedAt())
        .isEqualTo(clock.instant().truncatedTo(ChronoUnit.MICROS));
  }

  @Test
  void sendsTheCodeByEmailAndStoresOnlyItsHash() {
    UserAccount user = savedUser("ACTIVE");

    service.requestReset(EMAIL);

    assertThat(mail.sent).hasSize(1);
    var sent = mail.sent.getFirst();
    assertThat(sent.email()).isEqualTo(EMAIL);
    assertThat(sent.validFor()).isEqualTo(Duration.ofMinutes(30));
    assertThat(sent.code()).hasSizeGreaterThanOrEqualTo(40);
    assertThat(repository.storedResetHash(user.id()))
        .hasValueSatisfying(hash -> assertThat(hash).doesNotContain(sent.code()).hasSize(64));
    assertThat(repository.findResetTokenExpiry(user.id()))
        .contains(clock.instant().plus(Duration.ofMinutes(30)));
  }

  @Test
  void findsTheAccountIgnoringCaseAndSurroundingSpaces() {
    savedUser("ACTIVE");

    service.requestReset("  GUEST@Example.TEST ");

    assertThat(mail.sent).hasSize(1);
  }

  @Test
  void answersTheSameForAnUnknownEmailAndSendsNothing() {
    assertThatCode(() -> service.requestReset("nobody@example.test")).doesNotThrowAnyException();

    assertThat(mail.sent).isEmpty();
  }

  /**
   * TAR-201 (D-20): the answer must not take longer when the account exists. Before answering, the
   * request does the same thing for a known and an unknown email: it queues one task and touches
   * neither the database, the encoder nor the email; the lookup, the code and the email all run in
   * the task.
   */
  @Test
  void beforeAnsweringAKnownAndAnUnknownEmailDoTheSameWork() {
    savedUser("ACTIVE");
    UserRepositoryPort users = mock(UserRepositoryPort.class, delegatesTo(repository));
    PasswordEncoder spiedEncoder = mock(PasswordEncoder.class);
    PasswordResetMailPort spiedMail = mock(PasswordResetMailPort.class);
    List<Runnable> queued = new ArrayList<>();
    var deferred =
        new PasswordRecoveryService(
            new ResetCodeIssuer(users, clock),
            users,
            spiedEncoder,
            spiedMail,
            queued::add,
            grants,
            clock);

    deferred.requestReset(EMAIL);
    deferred.requestReset("nobody@example.test");

    assertThat(queued).hasSize(2);
    verifyNoInteractions(users, spiedEncoder, spiedMail, grants);

    queued.forEach(Runnable::run);
    verify(spiedMail).sendResetLink(eq(EMAIL), anyString(), eq(ResetCodeIssuer.CODE_VALIDITY));
  }

  @Test
  void sendsNothingToADisabledAccount() {
    UserAccount user = savedUser("INACTIVE");

    service.requestReset(EMAIL);

    assertThat(mail.sent).isEmpty();
    assertThat(repository.storedResetHash(user.id())).isEmpty();
  }

  @Test
  void withinFiveMinutesOfIssuingASecondRequestKeepsTheCodeAndSendsNothing() {
    UserAccount user = savedUser("ACTIVE");
    service.requestReset(EMAIL);
    String hash = repository.storedResetHash(user.id()).orElseThrow();
    Instant expiry = repository.findResetTokenExpiry(user.id()).orElseThrow();

    clock.advance(Duration.ofSeconds(61));
    service.requestReset(EMAIL);
    clock.advance(Duration.ofSeconds(3 * 60 + 58));
    service.requestReset(EMAIL);

    assertThat(mail.sent).hasSize(1);
    assertThat(repository.storedResetHash(user.id())).contains(hash);
    assertThat(repository.findResetTokenExpiry(user.id())).contains(expiry);
    assertThatCode(() -> service.resetPassword(mail.sent.getFirst().code(), NEW_PASSWORD))
        .doesNotThrowAnyException();
  }

  @Test
  void fiveMinutesAfterIssuingALiveCodeIsReplacedAndEmailedAgain() {
    UserAccount user = savedUser("ACTIVE");
    service.requestReset(EMAIL);
    String oldCode = mail.sent.getFirst().code();
    String oldHash = repository.storedResetHash(user.id()).orElseThrow();

    clock.advance(Duration.ofMinutes(5));
    service.requestReset(EMAIL);
    String newCode = mail.sent.getLast().code();

    assertThat(mail.sent).hasSize(2);
    assertThat(newCode).isNotEqualTo(oldCode);
    assertThat(repository.storedResetHash(user.id()))
        .isPresent()
        .isNotEqualTo(Optional.of(oldHash));
    assertThatThrownBy(() -> service.resetPassword(oldCode, NEW_PASSWORD))
        .isInstanceOf(InvalidResetCodeException.class);
    assertThatCode(() -> service.resetPassword(newCode, NEW_PASSWORD)).doesNotThrowAnyException();
  }

  @Test
  void anExpiredCodeIsReplacedByANewOneThatIsEmailed() {
    UserAccount user = savedUser("ACTIVE");
    service.requestReset(EMAIL);
    String oldCode = mail.sent.getFirst().code();
    String oldHash = repository.storedResetHash(user.id()).orElseThrow();

    clock.advance(Duration.ofMinutes(30));
    service.requestReset(EMAIL);
    String newCode = mail.sent.getLast().code();

    assertThat(mail.sent).hasSize(2);
    assertThat(newCode).isNotEqualTo(oldCode);
    assertThat(repository.storedResetHash(user.id()))
        .isPresent()
        .isNotEqualTo(Optional.of(oldHash));
    assertThatThrownBy(() -> service.resetPassword(oldCode, NEW_PASSWORD))
        .isInstanceOf(InvalidResetCodeException.class);
    assertThatCode(() -> service.resetPassword(newCode, NEW_PASSWORD)).doesNotThrowAnyException();
  }

  @Test
  void aUsedCodeLetsANewRequestIssueAnotherOne() {
    savedUser("ACTIVE");
    service.requestReset(EMAIL);
    service.resetPassword(mail.sent.getFirst().code(), NEW_PASSWORD);

    service.requestReset(EMAIL);

    assertThat(mail.sent).hasSize(2);
  }

  @Test
  void changingThePasswordClearsTheCodeSoANewRequestIssuesAnother() {
    UserAccount user = savedUser("ACTIVE");
    service.requestReset(EMAIL);
    repository.changePassword(user.id(), encoder.encode(NEW_PASSWORD), clock.instant());

    service.requestReset(EMAIL);

    assertThat(mail.sent).hasSize(2);
  }

  @Test
  void aFailedDeliveryIsNotShownToTheCaller() {
    savedUser("ACTIVE");
    mail.failWith(new MailDeliveryException("provider down", null));

    assertThatCode(() -> service.requestReset(EMAIL)).doesNotThrowAnyException();
  }

  @Test
  void resetsThePasswordAndClearsThePendingChange() {
    UserAccount user = savedUser("ACTIVE");
    service.requestReset(EMAIL);

    service.resetPassword(mail.sent.getFirst().code(), NEW_PASSWORD);

    UserAccount stored = repository.find(user.id());
    assertThat(encoder.matches(NEW_PASSWORD, stored.passwordHash())).isTrue();
    assertThat(stored.mustChangePassword()).isFalse();
    assertThat(repository.storedResetHash(user.id())).isEmpty();
  }

  @Test
  void theCodeWorksOnlyOnce() {
    savedUser("ACTIVE");
    service.requestReset(EMAIL);
    String code = mail.sent.getFirst().code();
    service.resetPassword(code, NEW_PASSWORD);

    assertThatThrownBy(() -> service.resetPassword(code, "Otra123456"))
        .isInstanceOf(InvalidResetCodeException.class);
  }

  @Test
  void anUnknownCodeIsInvalid() {
    assertThatThrownBy(() -> service.resetPassword("x".repeat(43), NEW_PASSWORD))
        .isInstanceOf(InvalidResetCodeException.class);
  }

  @Test
  void anExpiredCodeIsInvalid() {
    savedUser("ACTIVE");
    service.requestReset(EMAIL);
    String code = mail.sent.getFirst().code();

    clock.advance(Duration.ofMinutes(30));

    assertThatThrownBy(() -> service.resetPassword(code, NEW_PASSWORD))
        .isInstanceOf(InvalidResetCodeException.class);
  }

  @Test
  void aCodeOfAnAccountDisabledAfterwardsIsInvalid() {
    UserAccount user = savedUser("ACTIVE");
    service.requestReset(EMAIL);
    String code = mail.sent.getFirst().code();
    repository.save(
        new UserAccount(
            user.id(),
            user.email(),
            user.documentType(),
            user.documentNumber(),
            user.passwordHash(),
            user.role(),
            null,
            user.mustChangePassword(),
            "INACTIVE",
            0,
            null,
            null));

    assertThatThrownBy(() -> service.resetPassword(code, NEW_PASSWORD))
        .isInstanceOf(InvalidResetCodeException.class);
  }

  @Test
  void aWeakPasswordIsRejectedWithoutConsumingTheCode() {
    savedUser("ACTIVE");
    service.requestReset(EMAIL);
    String code = mail.sent.getFirst().code();

    assertThatThrownBy(() -> service.resetPassword(code, "corta"))
        .isInstanceOfSatisfying(
            WeakPasswordException.class,
            e -> assertThat(e.reason()).isEqualTo(WeakPasswordException.Reason.TOO_SHORT));
    assertThatCode(() -> service.resetPassword(code, NEW_PASSWORD)).doesNotThrowAnyException();
  }

  @Test
  void aPasswordLongerThanBcryptCanHashIsRejected() {
    savedUser("ACTIVE");
    service.requestReset(EMAIL);

    assertThatThrownBy(() -> service.resetPassword(mail.sent.getFirst().code(), "a".repeat(73)))
        .isInstanceOfSatisfying(
            WeakPasswordException.class,
            e -> assertThat(e.reason()).isEqualTo(WeakPasswordException.Reason.TOO_LONG));
  }

  private record Sent(String email, String code, Duration validFor) {}

  private static final class RecordingMail implements PasswordResetMailPort {
    final List<Sent> sent = new ArrayList<>();
    private @Nullable MailDeliveryException failure;

    void failWith(MailDeliveryException e) {
      this.failure = e;
    }

    @Override
    public void sendResetLink(String toEmail, String code, Duration validFor) {
      if (failure != null) {
        throw failure;
      }
      sent.add(new Sent(toEmail, code, validFor));
    }

    /** RF-22 only: the recovery never waits for the provider. */
    @Override
    public boolean sendAccessLinkNow(String toEmail, String code, Duration validFor) {
      throw new UnsupportedOperationException("The recovery sends in the background");
    }
  }

  // ------------------------------------------------------------------ TAR-36

  private UserAccount guestAccount(boolean pendingItsFirstPassword) {
    var user =
        new UserAccount(
            TestIds.next(),
            EMAIL,
            DocumentType.DNI,
            "22222222",
            encoder.encode("Antigua12345"),
            Role.GUEST,
            77L,
            pendingItsFirstPassword,
            "ACTIVE",
            0,
            null,
            null);
    repository.save(user);
    return user;
  }

  /** A guest pending activation gets its first password only through its access (RF-11). */
  @Test
  void aGuestPendingActivationGetsNoRecoveryCode() {
    guestAccount(true);

    service.requestReset(EMAIL);

    assertThat(mail.sent).isEmpty();
  }

  @Test
  void aResetRevokesTheAccessesOfTheGuest() {
    UserAccount user = guestAccount(false);
    service.requestReset(EMAIL);

    service.resetPassword(mail.sent.get(0).code(), "Nueva12345");

    org.mockito.Mockito.verify(grants).revokeLive(77L, clock.instant());
    assertThat(encoder.matches("Nueva12345", repository.find(user.id()).passwordHash())).isTrue();
  }

  /** TAR-36: a live access the administrator handed cannot become the password either. */
  @Test
  void aLiveAccessOfTheGuestCannotBeTheNewPassword() {
    UserAccount user = guestAccount(false);
    service.requestReset(EMAIL);
    org.mockito.Mockito.when(
            grants.isLiveAccess(77L, ResetCodeIssuer.hash("Enlace-vivo-123"), clock.instant()))
        .thenReturn(true);

    assertThatThrownBy(() -> service.resetPassword(mail.sent.get(0).code(), "Enlace-vivo-123"))
        .isInstanceOfSatisfying(
            WeakPasswordException.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(WeakPasswordException.Reason.SINGLE_USE_CREDENTIAL));
    assertThat(encoder.matches("Antigua12345", repository.find(user.id()).passwordHash())).isTrue();
  }

  @Test
  void theRecoveryCodeCannotBeTheNewPasswordAndIsKept() {
    UserAccount user = guestAccount(false);
    service.requestReset(EMAIL);
    String code = mail.sent.get(0).code();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resetPassword(code, code))
        .isInstanceOf(com.tarimatwasi.quipu.auth.application.port.in.WeakPasswordException.class);
    service.resetPassword(code, "Nueva12345");

    assertThat(encoder.matches("Nueva12345", repository.find(user.id()).passwordHash())).isTrue();
  }
}
