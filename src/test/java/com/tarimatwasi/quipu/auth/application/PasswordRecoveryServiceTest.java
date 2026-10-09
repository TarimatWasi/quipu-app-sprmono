package com.tarimatwasi.quipu.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.in.InvalidResetCodeException;
import com.tarimatwasi.quipu.auth.port.in.WeakPasswordException;
import com.tarimatwasi.quipu.auth.port.out.MailDeliveryException;
import com.tarimatwasi.quipu.auth.port.out.PasswordResetMailPort;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.TestIds;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** RF-16: the recovery code is single-use, expires, and the answer never reveals the account. */
class PasswordRecoveryServiceTest {

  private static final String EMAIL = "guest@example.test";
  private static final String NEW_PASSWORD = "Nueva12345";

  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-03T12:00:00Z"));
  private final RecordingMail mail = new RecordingMail();
  private InMemoryUserRepository repository;
  private PasswordRecoveryService service;

  @BeforeEach
  void setUp() {
    repository = new InMemoryUserRepository();
    service =
        new PasswordRecoveryService(
            new ResetCodeIssuer(repository, clock), repository, encoder, mail, clock);
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

  @Test
  void sendsNothingToADisabledAccount() {
    UserAccount user = savedUser("INACTIVE");

    service.requestReset(EMAIL);

    assertThat(mail.sent).isEmpty();
    assertThat(repository.storedResetHash(user.id())).isEmpty();
  }

  @Test
  void aSecondRequestWithinAMinuteDoesNotSendAnotherEmail() {
    savedUser("ACTIVE");
    service.requestReset(EMAIL);

    clock.advance(Duration.ofSeconds(59));
    service.requestReset(EMAIL);

    assertThat(mail.sent).hasSize(1);
  }

  @Test
  void aRequestExactlyAMinuteLaterSendsANewEmail() {
    savedUser("ACTIVE");
    service.requestReset(EMAIL);

    clock.advance(Duration.ofSeconds(60));
    service.requestReset(EMAIL);

    assertThat(mail.sent).hasSize(2);
  }

  @Test
  void aLaterRequestSendsANewCodeAndTheOldOneStopsWorking() {
    savedUser("ACTIVE");
    service.requestReset(EMAIL);
    String oldCode = mail.sent.getFirst().code();

    clock.advance(Duration.ofSeconds(61));
    service.requestReset(EMAIL);
    String newCode = mail.sent.getLast().code();

    assertThat(mail.sent).hasSize(2);
    assertThat(newCode).isNotEqualTo(oldCode);
    assertThatThrownBy(() -> service.resetPassword(oldCode, NEW_PASSWORD))
        .isInstanceOf(InvalidResetCodeException.class);
    assertThatCode(() -> service.resetPassword(newCode, NEW_PASSWORD)).doesNotThrowAnyException();
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
  }
}
