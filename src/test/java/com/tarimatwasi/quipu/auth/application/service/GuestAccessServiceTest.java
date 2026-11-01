package com.tarimatwasi.quipu.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.tarimatwasi.quipu.auth.application.port.in.AccountDocumentTakenException;
import com.tarimatwasi.quipu.auth.application.port.in.AccountEmailTakenException;
import com.tarimatwasi.quipu.auth.application.port.in.GuestAccessUseCase.AccountEmailChange;
import com.tarimatwasi.quipu.auth.application.port.in.GuestAccessUseCase.EmailChangeOutcome;
import com.tarimatwasi.quipu.auth.application.port.out.AccessGrantRepositoryPort;
import com.tarimatwasi.quipu.auth.application.port.out.AccessMailPort;
import com.tarimatwasi.quipu.auth.application.port.out.GuestAccountEmailNoticePort;
import com.tarimatwasi.quipu.auth.application.port.out.MailDeliveryException;
import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.support.MutableClock;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** TAR-229: the account email of a guest, set at the registration or by the administrator. */
class GuestAccessServiceTest {

  private static final Long GUEST = 7L;
  private static final Long OTHER_GUEST = 8L;
  private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

  private final InMemoryUserRepository users = new InMemoryUserRepository();
  private final GuestAccountEmailNoticePort notices = mock(GuestAccountEmailNoticePort.class);

  private final GuestAccessService service =
      new GuestAccessService(
          users,
          mock(AccessGrantRepositoryPort.class),
          mock(AccessMailPort.class),
          notices,
          new BCryptPasswordEncoder(4),
          new MutableClock(NOW));

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
            null));
  }

  @Test
  void aNewAccountKeepsAFreeEmailAsItsAccountEmail() {
    service.openNewAccount(GUEST, "DNI", "12345678", "ana@example.com");

    List<UserAccount> created = users.findAllByGuestIds(List.of(GUEST));
    assertThat(created).hasSize(1);
    assertThat(created.get(0).email()).isEqualTo("ana@example.com");
    assertThat(created.get(0).role()).isEqualTo(Role.GUEST);
  }

  @Test
  void aNewAccountRefusesAnEmailOfAnotherAccountInAnyCaseAndCreatesNothing() {
    account(1L, Role.ADMIN, null, "admin@example.com");

    assertThatThrownBy(() -> service.openNewAccount(GUEST, "DNI", "12345678", "ADMIN@Example.com"))
        .isInstanceOf(AccountEmailTakenException.class);
    assertThat(users.findAllByGuestIds(List.of(GUEST))).isEmpty();
  }

  @Test
  void aNewAccountRefusesADocumentOfAnotherAccount() {
    account(1L, Role.ADMIN, null, "admin@example.com");

    assertThatThrownBy(() -> service.openNewAccount(GUEST, "DNI", "00000001", "ana@example.com"))
        .isInstanceOf(AccountDocumentTakenException.class);
    assertThat(users.findAllByGuestIds(List.of(GUEST))).isEmpty();
  }

  @Test
  void aGuestWithoutAccountChangesNothing() {
    AccountEmailChange change = service.changeAccountEmail(GUEST, "ana@example.com");

    assertThat(change.outcome()).isEqualTo(EmailChangeOutcome.NO_ACCOUNT);
    assertThat(change.previousEmail()).isNull();
    assertThat(users.findByEmail("ana@example.com")).isEmpty();
  }

  @Test
  void aChangeWritesTheEmailRemovesTheRecoveryCodeAndAnswersThePreviousEmail() {
    account(10L, Role.GUEST, GUEST, "old@example.com");
    users.saveResetToken(10L, "hash-of-code", NOW.plusSeconds(600));

    AccountEmailChange change = service.changeAccountEmail(GUEST, "new@example.com");

    assertThat(change.outcome()).isEqualTo(EmailChangeOutcome.CHANGED);
    assertThat(change.previousEmail()).isEqualTo("old@example.com");
    assertThat(users.find(10L).email()).isEqualTo("new@example.com");
    assertThat(users.storedResetHash(10L)).isEmpty();
    assertThat(users.findPendingReset("hash-of-code")).isEmpty();
  }

  @Test
  void anAccountWithoutEmailGetsOneAndThereIsNoPreviousEmail() {
    account(10L, Role.GUEST, GUEST, null);

    AccountEmailChange change = service.changeAccountEmail(GUEST, "new@example.com");

    assertThat(change.outcome()).isEqualTo(EmailChangeOutcome.CHANGED);
    assertThat(change.previousEmail()).isNull();
    assertThat(users.find(10L).email()).isEqualTo("new@example.com");
  }

  @Test
  void theSameEmailInAnyCaseChangesNothingAndKeepsTheRecoveryCode() {
    account(10L, Role.GUEST, GUEST, "ana@example.com");
    users.saveResetToken(10L, "hash-of-code", NOW.plusSeconds(600));

    AccountEmailChange change = service.changeAccountEmail(GUEST, "ANA@example.com");

    assertThat(change.outcome()).isEqualTo(EmailChangeOutcome.UNCHANGED);
    assertThat(change.previousEmail()).isNull();
    assertThat(users.find(10L).email()).isEqualTo("ana@example.com");
    assertThat(users.storedResetHash(10L)).contains("hash-of-code");
  }

  @Test
  void anEmailOfAnotherAccountInAnyCaseIsRefusedAndNothingChanges() {
    account(10L, Role.GUEST, GUEST, "ana@example.com");
    account(11L, Role.GUEST, OTHER_GUEST, "beto@example.com");
    account(1L, Role.ADMIN, null, "admin@example.com");

    assertThatThrownBy(() -> service.changeAccountEmail(GUEST, "Beto@Example.com"))
        .isInstanceOf(AccountEmailTakenException.class);
    assertThatThrownBy(() -> service.changeAccountEmail(GUEST, "admin@example.com"))
        .isInstanceOf(AccountEmailTakenException.class);
    assertThat(users.find(10L).email()).isEqualTo("ana@example.com");
  }

  @Test
  void theNoticeGoesToThePreviousEmailAndAFailedOneIsOnlyLogged() {
    service.noticeAccountEmailChanged("old@example.com", "new@example.com");
    verify(notices).accountEmailChanged("old@example.com", "new@example.com");

    doThrow(new MailDeliveryException("provider down", null))
        .when(notices)
        .accountEmailChanged("old@example.com", "other@example.com");
    assertThatCode(() -> service.noticeAccountEmailChanged("old@example.com", "other@example.com"))
        .doesNotThrowAnyException();
  }

  @Test
  void theChangeNeverShowsAnEmailInItsText() {
    assertThat(new AccountEmailChange(EmailChangeOutcome.CHANGED, "old@example.com").toString())
        .doesNotContain("old@example.com")
        .contains("CHANGED");
  }
}
