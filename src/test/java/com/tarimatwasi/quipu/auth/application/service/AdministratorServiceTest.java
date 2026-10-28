package com.tarimatwasi.quipu.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tarimatwasi.quipu.auth.application.port.in.AdministratorDocumentTakenException;
import com.tarimatwasi.quipu.auth.application.port.in.AdministratorEmailTakenException;
import com.tarimatwasi.quipu.auth.application.port.in.AdministratorLimitReachedException;
import com.tarimatwasi.quipu.auth.application.port.in.AdministratorReauthenticationException;
import com.tarimatwasi.quipu.auth.application.port.in.CreateAdministratorUseCase.AdministratorCreated;
import com.tarimatwasi.quipu.auth.application.port.in.CreateAdministratorUseCase.CreateAdministratorCommand;
import com.tarimatwasi.quipu.auth.application.port.in.InvalidAdministratorDocumentException;
import com.tarimatwasi.quipu.auth.application.port.in.LoginUseCase.DocumentKind;
import com.tarimatwasi.quipu.auth.application.port.out.AccountTakenException;
import com.tarimatwasi.quipu.auth.application.port.out.AccountTakenException.Taken;
import com.tarimatwasi.quipu.auth.application.port.out.AdministratorNoticePort;
import com.tarimatwasi.quipu.auth.application.port.out.AdministratorStorePort;
import com.tarimatwasi.quipu.auth.application.port.out.MailDeliveryException;
import com.tarimatwasi.quipu.auth.application.port.out.PasswordResetMailPort;
import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.TestIds;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AdministratorServiceTest {

  private static final String PASSWORD = "Creador12345";
  private static final String CREATOR_EMAIL = "creador@example.test";
  private static final String NEW_EMAIL = "Nueva.Admin@example.test";
  private static final String NEW_DOCUMENT = "87654321";

  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-27T10:00:00Z"));
  private final InMemoryUserRepository users = new InMemoryUserRepository();
  private final FakeStore store = new FakeStore();
  private final PasswordResetMailPort mail = mock(PasswordResetMailPort.class);
  private final AdministratorNoticePort notices = mock(AdministratorNoticePort.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final List<Logger> loggers =
      List.of(
          (Logger) LoggerFactory.getLogger(AdministratorService.class),
          (Logger) LoggerFactory.getLogger(AdministratorRegistrar.class));

  private final Long creatorId = TestIds.next();
  private AdministratorService service = serviceWithLimit(5);

  private AdministratorService serviceWithLimit(int max) {
    return new AdministratorService(
        new AdministratorRegistrar(
            new AdministratorReauthentication(users, encoder, clock), store, max),
        new ResetCodeIssuer(users, clock),
        mail,
        notices,
        store,
        encoder);
  }

  @BeforeEach
  void oneAdministrator() {
    logs.start();
    loggers.forEach(logger -> logger.addAppender(logs));
    store.add(admin(creatorId, CREATOR_EMAIL, "11111111", false, "ACTIVE"));
    when(mail.sendAccessLinkNow(anyString(), anyString(), any())).thenReturn(true);
  }

  @AfterEach
  void releaseTheLog() {
    loggers.forEach(logger -> logger.detachAppender(logs));
  }

  private UserAccount admin(
      Long id, String email, String document, boolean mustChange, String status) {
    return new UserAccount(
        id,
        email,
        DocumentType.DNI,
        document,
        encoder.encode(PASSWORD),
        Role.ADMIN,
        null,
        mustChange,
        status,
        0,
        null,
        null);
  }

  private CreateAdministratorCommand command(String password) {
    return new CreateAdministratorCommand(
        creatorId, DocumentKind.DNI, NEW_DOCUMENT, NEW_EMAIL, password);
  }

  @Test
  void createsAnActiveAdministratorThatMustChooseItsPasswordAndEmailsItTheLink() {
    AdministratorCreated created = service.create(command(PASSWORD));

    UserAccount account = users.find(created.id());
    assertThat(account.role()).isEqualTo(Role.ADMIN);
    assertThat(account.status()).isEqualTo("ACTIVE");
    assertThat(account.mustChangePassword()).isTrue();
    assertThat(account.documentNumber()).isEqualTo(NEW_DOCUMENT);
    assertThat(account.email()).isEqualTo(NEW_EMAIL);
    assertThat(store.inserted.getFirst().createdBy()).isEqualTo(creatorId);
    // Nobody knows the password: neither the creator's nor an empty one opens the account.
    assertThat(account.passwordHash()).startsWith("$2");
    assertThat(encoder.matches(PASSWORD, account.passwordHash())).isFalse();
    assertThat(encoder.matches("", account.passwordHash())).isFalse();

    var code = ArgumentCaptor.forClass(String.class);
    verify(mail).sendAccessLinkNow(eq(NEW_EMAIL), code.capture(), eq(Duration.ofMinutes(30)));
    assertThat(users.storedResetHash(created.id())).contains(ResetCodeIssuer.hash(code.getValue()));
    assertThat(created.emailSent()).isTrue();
    verify(mail, never()).sendResetLink(anyString(), anyString(), any());
  }

  @Test
  void aWrongCurrentPasswordCreatesNothingAndCountsAsAFailedLogin() {
    assertThatThrownBy(() -> service.create(command("Incorrecta123")))
        .isInstanceOf(AdministratorReauthenticationException.class);

    assertThat(store.inserted).isEmpty();
    assertThat(store.events).doesNotContain("lock", "count");
    assertThat(users.find(creatorId).failedLoginAttempts()).isEqualTo(1);
    verifyNoInteractions(mail, notices);
  }

  /** SEG-06: the fifth failure locks the login; while it lasts even the right password fails. */
  @Test
  void fiveWrongPasswordsLockTheAccountAndThenTheRightOneIsRefusedToo() {
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> service.create(command("Incorrecta123")))
          .isInstanceOf(AdministratorReauthenticationException.class);
    }
    assertThat(users.find(creatorId).isLockedAt(clock.instant())).isTrue();

    assertThatThrownBy(() -> service.create(command(PASSWORD)))
        .isInstanceOf(AdministratorReauthenticationException.class);
    assertThat(store.inserted).isEmpty();

    clock.advance(Duration.ofMinutes(16));
    assertThat(service.create(command(PASSWORD)).id()).isNotNull();
  }

  @Test
  void theRightPasswordClearsTheEarlierFailures() {
    assertThatThrownBy(() -> service.create(command("Incorrecta123")))
        .isInstanceOf(AdministratorReauthenticationException.class);

    service.create(command(PASSWORD));

    assertThat(users.find(creatorId).failedLoginAttempts()).isZero();
  }

  @Test
  void theMaximumCountsEveryAdministratorAndIsCheckedUnderTheLock() {
    store.add(admin(TestIds.next(), "b@example.test", "11111112", false, "ACTIVE"));
    store.add(admin(TestIds.next(), "c@example.test", "11111113", true, "ACTIVE"));
    store.add(admin(TestIds.next(), "d@example.test", "11111114", false, "ACTIVE"));
    // An inactive administrator still takes a place.
    store.add(admin(TestIds.next(), "e@example.test", "11111115", false, "INACTIVE"));

    assertThatThrownBy(() -> service.create(command(PASSWORD)))
        .isInstanceOf(AdministratorLimitReachedException.class);

    assertThat(store.inserted).isEmpty();
    assertThat(store.events).containsExactly("lock", "count");
    verifyNoInteractions(mail, notices);
  }

  @Test
  void belowTheMaximumTheAccountIsCreatedAfterTheLockAndTheCount() {
    store.add(admin(TestIds.next(), "b@example.test", "11111112", false, "ACTIVE"));
    store.add(admin(TestIds.next(), "c@example.test", "11111113", false, "ACTIVE"));
    store.add(admin(TestIds.next(), "d@example.test", "11111114", false, "ACTIVE"));

    service.create(command(PASSWORD));

    assertThat(store.events).startsWith("lock", "count", "insert");
    assertThat(store.countAdministrators()).isEqualTo(5);
  }

  @Test
  void theMaximumIsConfigurable() {
    service = serviceWithLimit(2);
    service.create(command(PASSWORD));

    assertThatThrownBy(
            () ->
                service.create(
                    new CreateAdministratorCommand(
                        creatorId, DocumentKind.DNI, "22222222", "otra@example.test", PASSWORD)))
        .isInstanceOf(AdministratorLimitReachedException.class);
  }

  @Test
  void aMaximumBelowOneIsRefusedAtStartup() {
    assertThatThrownBy(
            () ->
                new AdministratorRegistrar(
                    new AdministratorReauthentication(users, encoder, clock), store, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anEmailTheProviderRefusesLeavesTheAccountCreatedWithEmailSentFalse() {
    when(mail.sendAccessLinkNow(anyString(), anyString(), any())).thenReturn(false);

    AdministratorCreated created = service.create(command(PASSWORD));

    assertThat(created.emailSent()).isFalse();
    assertThat(users.findById(created.id())).isPresent();
  }

  @Test
  void anEmailThatFailsLeavesTheAccountCreatedWithEmailSentFalse() {
    when(mail.sendAccessLinkNow(anyString(), anyString(), any()))
        .thenThrow(new MailDeliveryException("refused", null));

    AdministratorCreated created = service.create(command(PASSWORD));

    assertThat(created.emailSent()).isFalse();
    assertThat(users.findById(created.id())).isPresent();
  }

  @Test
  void theOtherActiveAdministratorsAreToldWhoCreatedWhichAccount() {
    store.add(admin(TestIds.next(), "b@example.test", "11111112", false, "ACTIVE"));
    store.add(admin(TestIds.next(), "c@example.test", "11111113", false, "INACTIVE"));

    AdministratorCreated created = service.create(command(PASSWORD));

    verify(notices).administratorCreated("b@example.test", CREATOR_EMAIL, NEW_EMAIL);
    verify(notices, never()).administratorCreated(eq("c@example.test"), anyString(), anyString());
    verify(notices, never()).administratorCreated(eq(CREATOR_EMAIL), anyString(), anyString());
    verify(notices, never()).administratorCreated(eq(NEW_EMAIL), anyString(), anyString());
    assertThat(store.lastExcluded.get()).containsExactlyInAnyOrder(creatorId, created.id());
  }

  @Test
  void aNoticeThatFailsDoesNotUndoTheCreationNorStopTheOtherNotices() {
    store.add(admin(TestIds.next(), "b@example.test", "11111112", false, "ACTIVE"));
    store.add(admin(TestIds.next(), "c@example.test", "11111113", false, "ACTIVE"));
    doThrow(new MailDeliveryException("down", null))
        .when(notices)
        .administratorCreated(eq("b@example.test"), anyString(), anyString());

    AdministratorCreated created = service.create(command(PASSWORD));

    assertThat(users.findById(created.id())).isPresent();
    assertThat(created.emailSent()).isTrue();
    verify(notices).administratorCreated("c@example.test", CREATOR_EMAIL, NEW_EMAIL);
  }

  @Test
  void aDniThatIsNotEightDigitsIsInvalidBeforeThePasswordIsChecked() {
    assertThatThrownBy(
            () ->
                service.create(
                    new CreateAdministratorCommand(
                        creatorId, DocumentKind.DNI, "1234", NEW_EMAIL, "Incorrecta123")))
        .isInstanceOf(InvalidAdministratorDocumentException.class);
    assertThatThrownBy(
            () ->
                service.create(
                    new CreateAdministratorCommand(
                        creatorId, DocumentKind.CE, "AB-12", NEW_EMAIL, PASSWORD)))
        .isInstanceOf(InvalidAdministratorDocumentException.class);

    assertThat(users.find(creatorId).failedLoginAttempts()).isZero();
    assertThat(store.inserted).isEmpty();
  }

  /** The same rule as the guests: no edge spaces, CE and passport in upper case. */
  @Test
  void theDocumentIsStoredAsTheGuestsStoreIt() {
    AdministratorCreated created =
        service.create(
            new CreateAdministratorCommand(
                creatorId, DocumentKind.CE, " ab12cd ", " " + NEW_EMAIL + " ", PASSWORD));

    UserAccount account = users.find(created.id());
    assertThat(account.documentType()).isEqualTo(DocumentType.CE);
    assertThat(account.documentNumber()).isEqualTo("AB12CD");
    assertThat(account.email()).isEqualTo(NEW_EMAIL);
  }

  @Test
  void aTakenDocumentOrEmailIsItsOwnConflict() {
    store.add(admin(TestIds.next(), "b@example.test", NEW_DOCUMENT, false, "ACTIVE"));
    assertThatThrownBy(() -> service.create(command(PASSWORD)))
        .isInstanceOf(AdministratorDocumentTakenException.class);

    assertThatThrownBy(
            () ->
                service.create(
                    new CreateAdministratorCommand(
                        creatorId,
                        DocumentKind.DNI,
                        "22222222",
                        CREATOR_EMAIL.toUpperCase(Locale.ROOT),
                        PASSWORD)))
        .isInstanceOf(AdministratorEmailTakenException.class);
    assertThat(store.inserted).isEmpty();
    verifyNoInteractions(mail, notices);
  }

  @Test
  void aSessionPendingItsPasswordChangeCannotCreateAnAdministrator() {
    Long pending = TestIds.next();
    store.add(admin(pending, "p@example.test", "11111119", true, "ACTIVE"));

    assertThatThrownBy(
            () ->
                service.create(
                    new CreateAdministratorCommand(
                        pending, DocumentKind.DNI, NEW_DOCUMENT, NEW_EMAIL, PASSWORD)))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(store.inserted).isEmpty();
  }

  /** Only internal ids reach the logs: no document, email, password or code. */
  @Test
  void theLogsCarryOnlyTheIdsOfTheAccounts() {
    when(mail.sendAccessLinkNow(anyString(), anyString(), any())).thenReturn(false);
    store.add(admin(TestIds.next(), "b@example.test", "11111112", false, "ACTIVE"));
    doThrow(new MailDeliveryException("down " + NEW_EMAIL, null))
        .when(notices)
        .administratorCreated(anyString(), anyString(), anyString());

    AdministratorCreated created = service.create(command(PASSWORD));

    var code = ArgumentCaptor.forClass(String.class);
    verify(mail).sendAccessLinkNow(anyString(), code.capture(), any());
    assertThat(logs.list).isNotEmpty();
    String all =
        String.join(
            "\n",
            logs.list.stream()
                .map(e -> e.getFormattedMessage() + " " + Objects.toString(e.getThrowableProxy()))
                .toList());
    assertThat(all)
        .contains(created.id().toString())
        .doesNotContainIgnoringCase(NEW_EMAIL)
        .doesNotContainIgnoringCase(CREATOR_EMAIL)
        .doesNotContain(NEW_DOCUMENT)
        .doesNotContain(PASSWORD)
        .doesNotContain(code.getValue());
  }

  /** The store of the tests: the unique rules of the database, over the user repository. */
  private final class FakeStore implements AdministratorStorePort {

    final List<String> events = new ArrayList<>();
    final List<NewAdministrator> inserted = new ArrayList<>();
    final List<UserAccount> admins = new ArrayList<>();
    final AtomicReference<Collection<Long>> lastExcluded = new AtomicReference<>(List.of());

    void add(UserAccount account) {
      admins.add(account);
      users.save(account);
    }

    @Override
    public void lockAdministrators() {
      events.add("lock");
    }

    @Override
    public int countAdministrators() {
      events.add("count");
      return admins.size();
    }

    @Override
    public Long insert(NewAdministrator administrator) {
      events.add("insert");
      if (users
          .findByDocument(administrator.documentType(), administrator.documentNumber())
          .isPresent()) {
        throw new AccountTakenException(Taken.DOCUMENT, new IllegalStateException("document"));
      }
      if (users.findByEmail(administrator.email()).isPresent()) {
        throw new AccountTakenException(Taken.EMAIL, new IllegalStateException("email"));
      }
      Long id = TestIds.next();
      inserted.add(administrator);
      add(
          new UserAccount(
              id,
              administrator.email(),
              administrator.documentType(),
              administrator.documentNumber(),
              administrator.passwordHash(),
              Role.ADMIN,
              null,
              true,
              "ACTIVE",
              0,
              null,
              null));
      return id;
    }

    @Override
    public List<String> activeAdministratorEmailsExcept(Collection<Long> excluded) {
      lastExcluded.set(List.copyOf(excluded));
      return admins.stream()
          .filter(a -> !a.isDisabled() && !excluded.contains(a.id()))
          .map(a -> Objects.requireNonNull(a.email()))
          .toList();
    }

    @Override
    public List<StoredAdministrator> listAdministrators() {
      throw new UnsupportedOperationException("not part of the creation");
    }

    @Override
    public Optional<StoredAdministrator> lockAdministrator(Long id) {
      throw new UnsupportedOperationException("not part of the creation");
    }

    @Override
    public void holdAsReference(Long id) {
      throw new UnsupportedOperationException("not part of the creation");
    }

    @Override
    public int countActiveAdministrators() {
      throw new UnsupportedOperationException("not part of the creation");
    }

    @Override
    public void setAdministratorStatus(Long id, String status, Long modifiedBy) {
      throw new UnsupportedOperationException("not part of the creation");
    }
  }
}
