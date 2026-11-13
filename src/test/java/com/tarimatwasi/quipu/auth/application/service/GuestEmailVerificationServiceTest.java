package com.tarimatwasi.quipu.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tarimatwasi.quipu.auth.application.port.in.AccountEmailTakenException;
import com.tarimatwasi.quipu.auth.application.port.in.EmailVerificationException;
import com.tarimatwasi.quipu.auth.application.port.in.EmailVerificationException.Reason;
import com.tarimatwasi.quipu.auth.application.port.out.GuestAccountEmailNoticePort;
import com.tarimatwasi.quipu.auth.application.port.out.GuestEmailVerificationMailPort;
import com.tarimatwasi.quipu.auth.application.port.out.GuestEmailVerificationStorePort;
import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.shared.changes.ChangeSignals;
import com.tarimatwasi.quipu.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class GuestEmailVerificationServiceTest {
  private final ChangeSignals changes = org.mockito.Mockito.mock(ChangeSignals.class);

  private static final Long GUEST = 7L;
  private static final Long ACCOUNT = 70L;
  private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
  private static final String CURRENT = "ana@example.com";
  private static final String NEW = "ana.nueva@example.com";

  private final InMemoryUserRepository users = new InMemoryUserRepository();
  private final InMemoryStore store = new InMemoryStore();
  private final GuestEmailVerificationMailPort mail = mock(GuestEmailVerificationMailPort.class);
  private final GuestAccountEmailNoticePort notices = mock(GuestAccountEmailNoticePort.class);
  private final MutableClock clock = new MutableClock(NOW);

  private final GuestEmailVerificationService service =
      new GuestEmailVerificationService(
          users,
          store,
          mail,
          new GuestAccountEmailChanger(users, notices, changes),
          new BCryptPasswordEncoder(4),
          clock);

  @BeforeEach
  void accounts() {
    account(ACCOUNT, Role.GUEST, GUEST, CURRENT);
    account(80L, Role.ADMIN, null, "admin@example.com");
  }

  private void account(Long id, Role role, @Nullable Long guestId, @Nullable String email) {
    users.save(
        new UserAccount(
            id,
            email,
            DocumentType.DNI,
            String.format("%08d", id),
            "hash",
            role,
            guestId,
            false,
            "ACTIVE",
            0,
            null,
            null,
            0L));
  }

  private String sentCode() {
    ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    verify(mail).send(eq(NEW), code.capture(), eq(10L));
    return code.getValue();
  }

  private static String wrong(String code) {
    char last = code.charAt(5);
    return code.substring(0, 5) + (last == '9' ? '0' : (char) (last + 1));
  }

  private static Reason reasonOf(Throwable thrown) {
    return ((EmailVerificationException) thrown).reason();
  }

  @Test
  void request_sendsASixDigitCodeAndKeepsOnlyItsHash() {
    service.request(GUEST, NEW);

    String code = sentCode();
    assertThat(code).matches("[0-9]{6}");
    assertThat(store.row(GUEST).codeHash()).isNotEqualTo(code);
    assertThat(store.row(GUEST).expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    assertThat(service.pendingEmail(GUEST)).contains(NEW);
    assertThat(users.find(ACCOUNT).email()).isEqualTo(CURRENT);
  }

  @Test
  void request_withinTheMinute_isACooldownWithTheSecondsLeft() {
    service.request(GUEST, NEW);
    clock.advance(Duration.ofSeconds(15));

    assertThatThrownBy(() -> service.request(GUEST, "otra@example.com"))
        .isInstanceOfSatisfying(
            EmailVerificationException.class,
            e -> {
              assertThat(e.reason()).isEqualTo(Reason.COOLDOWN);
              assertThat(e.retryAfterSeconds()).isEqualTo(45);
            });
  }

  @Test
  void request_ofItsOwnEmailOrOfAnotherAccount_sendsNothing() {
    assertThatThrownBy(() -> service.request(GUEST, "ANA@example.com"))
        .satisfies(e -> assertThat(reasonOf(e)).isEqualTo(Reason.UNCHANGED));
    assertThatThrownBy(() -> service.request(GUEST, "Admin@Example.com"))
        .isInstanceOf(AccountEmailTakenException.class);

    verifyNoInteractions(mail);
    assertThat(store.rows).isEmpty();
  }

  @Test
  void confirm_withTheCode_changesTheEmailAndTellsThePreviousOne() {
    service.request(GUEST, NEW);

    service.confirm(GUEST, sentCode());

    assertThat(users.find(ACCOUNT).email()).isEqualTo(NEW);
    assertThat(store.rows).isEmpty();
    verify(notices).accountEmailChanged(CURRENT, NEW);
  }

  @Test
  void confirm_withAWrongCode_countsTheAttemptAndKeepsTheEmail() {
    service.request(GUEST, NEW);
    String code = sentCode();

    assertThatThrownBy(() -> service.confirm(GUEST, wrong(code)))
        .satisfies(e -> assertThat(reasonOf(e)).isEqualTo(Reason.INVALID_CODE));

    assertThat(store.row(GUEST).attempts()).isEqualTo(1);
    assertThat(users.find(ACCOUNT).email()).isEqualTo(CURRENT);
  }

  @Test
  void confirm_afterFiveWrongCodes_expiresEvenWithTheRightOne() {
    service.request(GUEST, NEW);
    String code = sentCode();
    for (int attempt = 0; attempt < 5; attempt++) {
      assertThatThrownBy(() -> service.confirm(GUEST, wrong(code)))
          .isInstanceOf(EmailVerificationException.class);
    }
    assertThat(service.pendingEmail(GUEST)).isEmpty();

    assertThatThrownBy(() -> service.confirm(GUEST, code))
        .satisfies(e -> assertThat(reasonOf(e)).isEqualTo(Reason.EXPIRED));
    verify(notices, never()).accountEmailChanged(anyString(), anyString());
  }

  /** Codex P2 on #160: running out of attempts must not open the way to another code at once. */
  @Test
  void request_afterTheAttemptsRunOut_stillWaitsForTheMinute() {
    service.request(GUEST, NEW);
    String code = sentCode();
    for (int attempt = 0; attempt < 6; attempt++) {
      assertThatThrownBy(() -> service.confirm(GUEST, wrong(code)))
          .isInstanceOf(EmailVerificationException.class);
    }

    assertThatThrownBy(() -> service.request(GUEST, NEW))
        .satisfies(e -> assertThat(reasonOf(e)).isEqualTo(Reason.COOLDOWN));
    clock.advance(Duration.ofSeconds(60));
    service.request(GUEST, "otra@example.com");
    assertThat(store.row(GUEST).attempts()).isZero();
  }

  @Test
  void confirm_afterTenMinutes_expires() {
    service.request(GUEST, NEW);
    String code = sentCode();
    clock.advance(Duration.ofMinutes(10));

    assertThatThrownBy(() -> service.confirm(GUEST, code))
        .satisfies(e -> assertThat(reasonOf(e)).isEqualTo(Reason.EXPIRED));
    assertThat(users.find(ACCOUNT).email()).isEqualTo(CURRENT);
  }

  @Test
  void confirm_withNothingPending_isAnInvalidCode() {
    assertThatThrownBy(() -> service.confirm(GUEST, "123456"))
        .satisfies(e -> assertThat(reasonOf(e)).isEqualTo(Reason.INVALID_CODE));
  }

  @Test
  void confirm_whenAnotherAccountTookTheEmailMeanwhile_isTaken() {
    service.request(GUEST, NEW);
    String code = sentCode();
    account(90L, Role.GUEST, 9L, NEW);

    assertThatThrownBy(() -> service.confirm(GUEST, code))
        .isInstanceOf(AccountEmailTakenException.class);
    verify(notices, never()).accountEmailChanged(anyString(), anyString());
  }

  @Test
  void aGuestWithoutAccountIsCorruptData() {
    assertThatThrownBy(() -> service.request(99L, NEW)).isInstanceOf(IllegalStateException.class);
    verify(mail, never()).send(anyString(), anyString(), anyLong());
  }

  /** The table, one row per guest. */
  private static final class InMemoryStore implements GuestEmailVerificationStorePort {

    final Map<Long, PendingEmailVerification> rows = new HashMap<>();

    PendingEmailVerification row(Long guestId) {
      return Objects.requireNonNull(rows.get(guestId));
    }

    @Override
    public Optional<PendingEmailVerification> find(Long guestId) {
      return Optional.ofNullable(rows.get(guestId));
    }

    @Override
    public void replace(
        Long guestId, String email, String codeHash, Instant expiresAt, Instant sentAt) {
      rows.put(guestId, new PendingEmailVerification(email, codeHash, expiresAt, sentAt, 0));
    }

    @Override
    public void addFailedAttempt(Long guestId) {
      PendingEmailVerification row = row(guestId);
      rows.put(
          guestId,
          new PendingEmailVerification(
              row.email(), row.codeHash(), row.expiresAt(), row.lastSentAt(), row.attempts() + 1));
    }

    @Override
    public void delete(Long guestId) {
      rows.remove(guestId);
    }
  }
}
