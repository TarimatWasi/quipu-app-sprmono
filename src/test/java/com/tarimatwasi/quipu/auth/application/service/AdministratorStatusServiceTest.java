package com.tarimatwasi.quipu.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tarimatwasi.quipu.auth.application.port.in.AdministratorNotFoundException;
import com.tarimatwasi.quipu.auth.application.port.in.AdministratorReauthenticationException;
import com.tarimatwasi.quipu.auth.application.port.in.AdministratorSelfDeactivationException;
import com.tarimatwasi.quipu.auth.application.port.in.LastActiveAdministratorException;
import com.tarimatwasi.quipu.auth.application.port.in.LoginUseCase.DocumentKind;
import com.tarimatwasi.quipu.auth.application.port.in.ManageAdministratorsUseCase.AdministratorStatus;
import com.tarimatwasi.quipu.auth.application.port.in.ManageAdministratorsUseCase.AdministratorView;
import com.tarimatwasi.quipu.auth.application.port.in.ManageAdministratorsUseCase.StatusChangeCommand;
import com.tarimatwasi.quipu.auth.application.port.out.AdministratorNoticePort;
import com.tarimatwasi.quipu.auth.application.port.out.AdministratorNoticePort.StatusChange;
import com.tarimatwasi.quipu.auth.application.port.out.AdministratorStorePort;
import com.tarimatwasi.quipu.auth.application.port.out.MailDeliveryException;
import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.TestIds;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** TAR-127: deactivating and reactivating administrators, without a database. */
class AdministratorStatusServiceTest {

  private static final String PASSWORD = "Actor12345";
  private static final String ACTOR_EMAIL = "actor@example.test";
  private static final String TARGET_EMAIL = "destino@example.test";
  private static final String THIRD_EMAIL = "tercera@example.test";
  private static final String TARGET_DOCUMENT = "87654321";

  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
  private final String hash = encoder.encode(PASSWORD);
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-27T10:00:00Z"));
  private final InMemoryUserRepository users = new InMemoryUserRepository();
  private final FakeStore store = new FakeStore();
  private final AdministratorNoticePort notices = mock(AdministratorNoticePort.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final List<Logger> loggers =
      List.of(
          (Logger) LoggerFactory.getLogger(AdministratorStatusService.class),
          (Logger) LoggerFactory.getLogger(AdministratorStatusChanger.class));

  private final Long actorId = TestIds.next();
  private final Long targetId = TestIds.next();
  private final Long thirdId = TestIds.next();
  private final AdministratorStatusService service =
      new AdministratorStatusService(
          new AdministratorReauthentication(users, encoder, clock),
          new AdministratorStatusChanger(users, store),
          store,
          notices);

  @BeforeEach
  void threeAdministrators() {
    logs.start();
    loggers.forEach(logger -> logger.addAppender(logs));
    store.add(account(actorId, ACTOR_EMAIL, "11111111", Role.ADMIN, "ACTIVE", false), "01");
    store.add(account(targetId, TARGET_EMAIL, TARGET_DOCUMENT, Role.ADMIN, "ACTIVE", false), "02");
    store.add(account(thirdId, THIRD_EMAIL, "11111113", Role.ADMIN, "ACTIVE", false), "03");
  }

  @AfterEach
  void releaseTheLog() {
    loggers.forEach(logger -> logger.detachAppender(logs));
  }

  private UserAccount account(
      Long id, String email, String document, Role role, String status, boolean mustChange) {
    return new UserAccount(
        id, email, DocumentType.DNI, document, hash, role, null, mustChange, status, 0, null, null);
  }

  private StatusChangeCommand command(@Nullable Long administrator, String password) {
    return new StatusChangeCommand(actorId, administrator, password);
  }

  private String statusOf(Long id) {
    return users.find(id).status();
  }

  @Test
  void deactivatesAnotherAdministratorUnderTheLockAndTellsTheOthers() {
    service.deactivate(command(targetId, PASSWORD));

    assertThat(statusOf(targetId)).isEqualTo("INACTIVE");
    assertThat(store.modifiedBy).containsExactly(Map.entry(targetId, actorId));
    // The rows (the account, the actor), then the lock of the administrators, the count and the
    // change.
    assertThat(store.events)
        .containsExactly(
            "lockAdministrator", "holdReference", "lock", "countActive", "setStatus", "emails");
    assertThat(store.lastExcluded).containsExactlyInAnyOrder(actorId, targetId);
    verify(notices)
        .administratorStatusChanged(
            THIRD_EMAIL, ACTOR_EMAIL, TARGET_EMAIL, StatusChange.DEACTIVATED);
    verifyNoMoreInteractions(notices);
  }

  @Test
  void reactivatesAnInactiveAdministratorAndTellsTheOthers() {
    store.setStatus(targetId, "INACTIVE");

    service.reactivate(command(targetId, PASSWORD));

    assertThat(statusOf(targetId)).isEqualTo("ACTIVE");
    assertThat(store.modifiedBy).containsExactly(Map.entry(targetId, actorId));
    // Reactivating cannot leave anyone without an active administrator: nothing is counted.
    assertThat(store.events)
        .containsExactly("lockAdministrator", "holdReference", "lock", "setStatus", "emails");
    verify(notices)
        .administratorStatusChanged(
            THIRD_EMAIL, ACTOR_EMAIL, TARGET_EMAIL, StatusChange.REACTIVATED);
    verifyNoMoreInteractions(notices);
  }

  @Test
  void deactivatingAnInactiveOneOrReactivatingAnActiveOneChangesNothing() {
    store.setStatus(targetId, "INACTIVE");
    service.deactivate(command(targetId, PASSWORD));
    assertThat(statusOf(targetId)).isEqualTo("INACTIVE");

    service.reactivate(command(thirdId, PASSWORD));
    assertThat(statusOf(thirdId)).isEqualTo("ACTIVE");

    assertThat(store.modifiedBy).isEmpty();
    assertThat(store.events).doesNotContain("setStatus", "emails");
    verifyNoInteractions(notices);
  }

  /**
   * Before anything else: a wrong password tells nothing about the id, whether it exists or not.
   */
  @Test
  void aWrongPasswordIsRefusedBeforeTheIdIsLookedUpAndCountsAsAFailedLogin() {
    Long unknown = TestIds.next();

    assertThatThrownBy(() -> service.deactivate(command(unknown, "Incorrecta123")))
        .isInstanceOf(AdministratorReauthenticationException.class);
    assertThatThrownBy(() -> service.reactivate(command(null, "Incorrecta123")))
        .isInstanceOf(AdministratorReauthenticationException.class);
    assertThatThrownBy(() -> service.deactivate(command(actorId, "Incorrecta123")))
        .isInstanceOf(AdministratorReauthenticationException.class);

    assertThat(store.events).isEmpty();
    assertThat(users.find(actorId).failedLoginAttempts()).isEqualTo(3);
    assertThat(statusOf(actorId)).isEqualTo("ACTIVE");
    verifyNoInteractions(notices);
  }

  /** SEG-06, as when creating: the fifth failure locks; while it lasts the right one fails too. */
  @Test
  void fiveWrongPasswordsLockTheAccountAndThenTheRightOneIsRefusedToo() {
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> service.deactivate(command(targetId, "Incorrecta123")))
          .isInstanceOf(AdministratorReauthenticationException.class);
    }
    assertThat(users.find(actorId).isLockedAt(clock.instant())).isTrue();

    assertThatThrownBy(() -> service.deactivate(command(targetId, PASSWORD)))
        .isInstanceOf(AdministratorReauthenticationException.class);
    assertThat(statusOf(targetId)).isEqualTo("ACTIVE");

    clock.advance(Duration.ofMinutes(16));
    service.deactivate(command(targetId, PASSWORD));
    assertThat(statusOf(targetId)).isEqualTo("INACTIVE");
    assertThat(users.find(actorId).failedLoginAttempts()).isZero();
  }

  @Test
  void anUnknownIdAGuestAndAnIdNeverIssuedAreAnAdministratorThatDoesNotExist() {
    Long guest = TestIds.next();
    users.save(account(guest, "huesped@example.test", "22222222", Role.GUEST, "ACTIVE", false));

    assertThatThrownBy(() -> service.deactivate(command(TestIds.next(), PASSWORD)))
        .isInstanceOf(AdministratorNotFoundException.class);
    assertThatThrownBy(() -> service.deactivate(command(guest, PASSWORD)))
        .isInstanceOf(AdministratorNotFoundException.class);
    assertThatThrownBy(() -> service.reactivate(command(guest, PASSWORD)))
        .isInstanceOf(AdministratorNotFoundException.class);
    assertThatThrownBy(() -> service.reactivate(command(null, PASSWORD)))
        .isInstanceOf(AdministratorNotFoundException.class);

    assertThat(statusOf(guest)).isEqualTo("ACTIVE");
    assertThat(store.events).doesNotContain("lock", "setStatus");
    assertThat(users.find(actorId).failedLoginAttempts()).isZero();
    verifyNoInteractions(notices);
  }

  /** D-70 a. */
  @Test
  void anAdministratorCannotDeactivateTheirOwnAccount() {
    assertThatThrownBy(() -> service.deactivate(command(actorId, PASSWORD)))
        .isInstanceOf(AdministratorSelfDeactivationException.class);

    assertThat(statusOf(actorId)).isEqualTo("ACTIVE");
    assertThat(store.events).doesNotContain("setStatus");
    verifyNoInteractions(notices);
  }

  @Test
  void reactivatingTheirOwnActiveAccountChangesNothing() {
    service.reactivate(command(actorId, PASSWORD));

    assertThat(statusOf(actorId)).isEqualTo("ACTIVE");
    assertThat(store.modifiedBy).isEmpty();
  }

  /**
   * While D-70 a holds the actor is an active administrator other than the target, so this is the
   * guard of the rule should another check change: the count under the lock decides.
   */
  @Test
  void theLastActiveAdministratorIsNeverDeactivated() {
    store.activeCountOverride = 1;

    assertThatThrownBy(() -> service.deactivate(command(targetId, PASSWORD)))
        .isInstanceOf(LastActiveAdministratorException.class);

    assertThat(statusOf(targetId)).isEqualTo("ACTIVE");
    assertThat(store.events)
        .containsExactly("lockAdministrator", "holdReference", "lock", "countActive");
    verifyNoInteractions(notices);
  }

  @Test
  void twoActiveAdministratorsAreEnoughToDeactivateOne() {
    store.setStatus(thirdId, "INACTIVE");

    service.deactivate(command(targetId, PASSWORD));

    assertThat(statusOf(targetId)).isEqualTo("INACTIVE");
    assertThat(statusOf(actorId)).isEqualTo("ACTIVE");
  }

  /** Checked under the lock: an actor deactivated meanwhile by another one changes nothing. */
  @Test
  void anActorThatIsNoLongerAnActiveAdministratorUnderTheLockIsRefused() {
    store.onLock =
        () -> users.save(account(actorId, ACTOR_EMAIL, "11111111", Role.ADMIN, "INACTIVE", false));

    assertThatThrownBy(() -> service.deactivate(command(targetId, PASSWORD)))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(statusOf(targetId)).isEqualTo("ACTIVE");
    assertThat(store.events).doesNotContain("setStatus");
  }

  @Test
  void aSessionPendingItsPasswordChangeOrOfAnotherRoleIsRefused() {
    Long pending = TestIds.next();
    store.add(account(pending, "p@example.test", "11111119", Role.ADMIN, "ACTIVE", true), "04");
    Long guest = TestIds.next();
    users.save(account(guest, "g@example.test", "22222222", Role.GUEST, "ACTIVE", false));

    assertThatThrownBy(
            () -> service.deactivate(new StatusChangeCommand(pending, targetId, PASSWORD)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.reactivate(new StatusChangeCommand(guest, targetId, PASSWORD)))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(statusOf(targetId)).isEqualTo("ACTIVE");
    assertThat(store.events).isEmpty();
  }

  @Test
  void aNoticeThatFailsDoesNotUndoTheChangeNorStopTheOtherNotices() {
    Long fourth = TestIds.next();
    store.add(
        account(fourth, "cuarta@example.test", "11111114", Role.ADMIN, "ACTIVE", false), "04");
    doThrow(new MailDeliveryException("down", null))
        .when(notices)
        .administratorStatusChanged(eq(THIRD_EMAIL), anyString(), anyString(), any());

    service.deactivate(command(targetId, PASSWORD));

    assertThat(statusOf(targetId)).isEqualTo("INACTIVE");
    verify(notices)
        .administratorStatusChanged(
            "cuarta@example.test", ACTOR_EMAIL, TARGET_EMAIL, StatusChange.DEACTIVATED);
  }

  @Test
  void theOthersThatCannotBeReadDoNotUndoTheChange() {
    store.failEmails = true;

    service.deactivate(command(targetId, PASSWORD));

    assertThat(statusOf(targetId)).isEqualTo("INACTIVE");
    verifyNoInteractions(notices);
  }

  /** Only internal ids reach the logs: no document, email or password. */
  @Test
  void theLogsCarryOnlyTheIdsOfTheAccounts() {
    doThrow(new MailDeliveryException("down " + THIRD_EMAIL, null))
        .when(notices)
        .administratorStatusChanged(anyString(), anyString(), anyString(), any());

    service.deactivate(command(targetId, PASSWORD));
    service.reactivate(command(targetId, PASSWORD));

    assertThat(logs.list).isNotEmpty();
    String all =
        String.join(
            "\n",
            logs.list.stream()
                .map(e -> e.getFormattedMessage() + " " + Objects.toString(e.getThrowableProxy()))
                .toList());
    assertThat(all)
        .contains(targetId.toString())
        .contains(actorId.toString())
        .doesNotContainIgnoringCase(TARGET_EMAIL)
        .doesNotContainIgnoringCase(ACTOR_EMAIL)
        .doesNotContainIgnoringCase(THIRD_EMAIL)
        .doesNotContain(TARGET_DOCUMENT)
        .doesNotContain(PASSWORD);
  }

  @Test
  void theCommandNeverPrintsThePassword() {
    assertThat(command(targetId, PASSWORD).toString())
        .doesNotContain(PASSWORD)
        .contains(targetId.toString());
  }

  @Test
  void listsEveryAdministratorTheOldestFirstWithTheDocumentMaskedAndSelf() {
    store.setStatus(thirdId, "INACTIVE");
    Long passport = TestIds.next();
    store.add(
        new UserAccount(
            passport,
            "pasaporte@example.test",
            DocumentType.PASSPORT,
            "AB12",
            hash,
            Role.ADMIN,
            null,
            false,
            "ACTIVE",
            0,
            null,
            null),
        "00");

    List<AdministratorView> list = service.list(actorId);

    assertThat(list)
        .containsExactly(
            view(passport, DocumentKind.PASSPORT, "*B12", "pasaporte@example.test", true, "00"),
            view(actorId, DocumentKind.DNI, "*****111", ACTOR_EMAIL, true, "01"),
            view(targetId, DocumentKind.DNI, "*****321", TARGET_EMAIL, true, "02"),
            view(thirdId, DocumentKind.DNI, "*****113", THIRD_EMAIL, false, "03"));
  }

  /** The view expected for the session of the actor. */
  private AdministratorView view(
      Long id, DocumentKind type, String masked, String email, boolean active, String minute) {
    return new AdministratorView(
        id,
        type,
        masked,
        email,
        active ? AdministratorStatus.ACTIVE : AdministratorStatus.INACTIVE,
        store.createdAt(minute),
        id.equals(actorId));
  }

  @Test
  void aDocumentOfThreeCharactersOrLessIsNotMaskedFurther() {
    assertThat(AdministratorStatusService.maskDocument("123")).isEqualTo("123");
    assertThat(AdministratorStatusService.maskDocument("12")).isEqualTo("12");
    assertThat(AdministratorStatusService.maskDocument("1234")).isEqualTo("*234");
  }

  /** The store of the tests, over the user repository, recording the order of the calls. */
  private final class FakeStore implements AdministratorStorePort {

    final List<String> events = new ArrayList<>();
    final Map<Long, Long> modifiedBy = new HashMap<>();
    final Map<Long, Instant> created = new HashMap<>();
    final List<Long> admins = new ArrayList<>();
    Collection<Long> lastExcluded = List.of();
    @Nullable Integer activeCountOverride;
    boolean failEmails;
    Runnable onLock = () -> {};

    void add(UserAccount account, String minute) {
      users.save(account);
      admins.add(account.id());
      created.put(account.id(), createdAt(minute));
    }

    Instant createdAt(String minute) {
      return Instant.parse("2026-01-01T10:" + minute + ":00Z");
    }

    void setStatus(Long id, String status) {
      UserAccount a = users.find(id);
      users.save(
          new UserAccount(
              a.id(),
              a.email(),
              a.documentType(),
              a.documentNumber(),
              a.passwordHash(),
              a.role(),
              a.guestId(),
              a.mustChangePassword(),
              status,
              a.failedLoginAttempts(),
              a.lockedUntil(),
              a.passwordChangedAt()));
    }

    private StoredAdministrator stored(UserAccount a) {
      return new StoredAdministrator(
          a.id(),
          a.documentType(),
          a.documentNumber(),
          Objects.requireNonNull(a.email()),
          a.status(),
          Objects.requireNonNull(created.get(a.id())));
    }

    @Override
    public void lockAdministrators() {
      events.add("lock");
      onLock.run();
    }

    @Override
    public int countAdministrators() {
      events.add("count");
      return admins.size();
    }

    @Override
    public Long insert(NewAdministrator administrator) {
      throw new UnsupportedOperationException("not part of these tests");
    }

    @Override
    public List<String> activeAdministratorEmailsExcept(Collection<Long> excluded) {
      events.add("emails");
      if (failEmails) {
        throw new org.springframework.dao.DataAccessResourceFailureException("down");
      }
      lastExcluded = List.copyOf(excluded);
      return admins.stream()
          .map(users::find)
          .filter(a -> !a.isDisabled() && !excluded.contains(a.id()))
          .map(a -> Objects.requireNonNull(a.email()))
          .toList();
    }

    @Override
    public List<StoredAdministrator> listAdministrators() {
      return admins.stream()
          .map(users::find)
          .map(this::stored)
          .sorted(Comparator.comparing(StoredAdministrator::createdAt))
          .toList();
    }

    @Override
    public Optional<StoredAdministrator> lockAdministrator(Long id) {
      events.add("lockAdministrator");
      return users.findById(id).filter(a -> a.role() == Role.ADMIN).map(this::stored);
    }

    @Override
    public void holdAsReference(Long id) {
      events.add("holdReference");
    }

    @Override
    public int countActiveAdministrators() {
      events.add("countActive");
      if (activeCountOverride != null) {
        return activeCountOverride;
      }
      return (int) admins.stream().map(users::find).filter(a -> !a.isDisabled()).count();
    }

    @Override
    public void setAdministratorStatus(Long id, String status, Long by) {
      events.add("setStatus");
      setStatus(id, status);
      modifiedBy.put(id, by);
    }
  }
}
