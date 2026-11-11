package com.tarimatwasi.quipu.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.application.port.in.IssuedSession;
import com.tarimatwasi.quipu.auth.application.port.in.NoActiveSessionException;
import com.tarimatwasi.quipu.auth.application.port.out.SessionStorePort;
import com.tarimatwasi.quipu.auth.application.port.out.SessionStorePort.RevokedReason;
import com.tarimatwasi.quipu.auth.application.port.out.SessionTokenPort;
import com.tarimatwasi.quipu.auth.application.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.shared.changes.ChangeSignals;
import com.tarimatwasi.quipu.support.InMemorySessionStore;
import com.tarimatwasi.quipu.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * TAR-228: the sessions renewed by refresh tokens. Decided 2026-11-09: access of 15 minutes,
 * refresh that dies after 7 days unused and 30 days after the login, several sessions per account,
 * and a reuse closes only its own session, except within 30 seconds of the rotation.
 */
@ExtendWith(OutputCaptureExtension.class)
class SessionServiceTest {

  private static final Long USER = 41L;
  private static final Instant NOW = Instant.parse("2026-11-09T15:00:00Z");

  private final MutableClock clock = new MutableClock(NOW);
  private final InMemorySessionStore store = new InMemorySessionStore();
  private final UserRepositoryPort users = mock(UserRepositoryPort.class);
  private final ChangeSignals signals = mock(ChangeSignals.class);
  private long version;
  private String status = "ACTIVE";

  /** The access token names its user and session, so the tests can read them back. */
  private final SessionTokenPort tokens =
      new SessionTokenPort() {
        @Override
        public String issue(String userId) {
          throw new AssertionError("Sessions are opened by SessionService, never by issue()");
        }

        @Override
        public String renew(String userId, String sessionId) {
          return "access:" + userId + ":" + sessionId;
        }

        @Override
        public String issueTemporary(
            String userId, String sessionId, Long grantId, Instant expiresAt) {
          return "temporary:" + userId + ":" + sessionId;
        }

        @Override
        public Optional<String> sessionIdOf(String accessToken) {
          return accessToken.startsWith("access:") || accessToken.startsWith("temporary:")
              ? Optional.of(accessToken.substring(accessToken.lastIndexOf(':') + 1))
              : Optional.empty();
        }
      };

  private final SessionService service =
      new SessionService(
          store,
          tokens,
          users,
          new SessionPolicy(Duration.ofDays(7), Duration.ofDays(30), Duration.ofSeconds(30)),
          clock,
          signals);

  @BeforeEach
  void anActiveAccount() {
    when(users.securityVersion(USER)).thenAnswer(call -> version);
    when(users.findById(USER)).thenAnswer(call -> Optional.of(account()));
  }

  private UserAccount account() {
    return new UserAccount(
        USER,
        "ana@example.test",
        DocumentType.DNI,
        "12345678",
        "hash",
        Role.GUEST,
        7L,
        false,
        status,
        0,
        null,
        null,
        version);
  }

  private static String refreshOf(IssuedSession session) {
    return Objects.requireNonNull(session.refreshToken(), "a full session has a refresh token");
  }

  private static String sidOf(IssuedSession session) {
    return session.accessToken().substring(session.accessToken().lastIndexOf(':') + 1);
  }

  @Test
  void aLoginOpensASessionWhoseRefreshTokenIsBoundToItAndStoredOnlyAsAHash() {
    IssuedSession session = service.open(USER.toString());

    String refresh = session.refreshToken();
    assertThat(refresh).matches("[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}");
    assertThat(refresh).startsWith(sidOf(session) + ".");
    assertThat(session.refreshMaxAge()).isEqualTo(Duration.ofDays(7));
    var row = store.only();
    assertThat(row.sidHash)
        .isEqualTo(SessionStorePort.hash(sidOf(session)))
        .isNotEqualTo(sidOf(session));
    assertThat(row.absoluteExpiresAt).isEqualTo(NOW.plus(Duration.ofDays(30)));
    assertThat(store.tokens())
        .singleElement()
        .satisfies(token -> assertThat(refresh).doesNotContain(token.tokenHash));
  }

  @Test
  void aRefreshRotatesTheTokenAndKeepsTheSession() {
    IssuedSession first = service.open(USER.toString());
    clock.advance(Duration.ofMinutes(16));

    IssuedSession second = service.refresh(refreshOf(first)).orElseThrow();

    assertThat(sidOf(second)).isEqualTo(sidOf(first));
    assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
    assertThat(store.tokens()).hasSize(2);
    assertThat(store.tokens().get(0).rotatedAt).isEqualTo(clock.instant());
    assertThat(store.tokens().get(1).rotatedAt).isNull();
    assertThat(store.only().lastUsedAt).isEqualTo(clock.instant());
  }

  @Test
  void aRotatedTokenWithinThirtySecondsIsARaceOfTabsAndChangesNothing() {
    IssuedSession first = service.open(USER.toString());
    assertThat(service.refresh(refreshOf(first))).isPresent();
    clock.advance(Duration.ofSeconds(29));

    assertThat(service.refresh(refreshOf(first))).isEmpty();
    assertThat(store.only().revokedAt).isNull();
    assertThat(store.tokens()).hasSize(2);
  }

  @Test
  void aRotatedTokenAfterThirtySecondsIsAReuseAndClosesOnlyItsSession(CapturedOutput output) {
    IssuedSession stolen = service.open(USER.toString());
    IssuedSession otherDevice = service.open(USER.toString());
    IssuedSession renewed = service.refresh(refreshOf(stolen)).orElseThrow();
    clock.advance(Duration.ofSeconds(30));

    assertThatThrownBy(() -> service.refresh(refreshOf(stolen)))
        .isInstanceOf(NoActiveSessionException.class);

    var rows = store.sessions();
    assertThat(rows.get(0).revokedReason).isEqualTo(RevokedReason.REUSE);
    verify(signals).revokeSession(USER, SessionStorePort.hash(sidOf(stolen)));
    assertThat(rows.get(1).revokedAt).as("the other device stays").isNull();
    // The legitimate holder of the session is out too: its renewed token no longer works.
    assertThatThrownBy(() -> service.refresh(refreshOf(renewed)))
        .isInstanceOf(NoActiveSessionException.class);
    assertThat(service.refresh(refreshOf(otherDevice))).isPresent();
    assertThat(output)
        .contains("A rotated refresh token was used again")
        .doesNotContain(refreshOf(stolen))
        .doesNotContain(sidOf(stolen));
  }

  @Test
  void aSessionUnusedForSevenDaysEnds() {
    IssuedSession session = service.open(USER.toString());
    clock.advance(Duration.ofDays(7));

    assertThatThrownBy(() -> service.refresh(refreshOf(session)))
        .isInstanceOf(NoActiveSessionException.class);
    assertThat(store.only().revokedReason).isEqualTo(RevokedReason.INVALIDATED);
    verify(signals).revokeSession(USER, SessionStorePort.hash(sidOf(session)));
  }

  @Test
  void noRefreshTakesASessionPastThirtyDaysFromItsLogin() {
    IssuedSession session = service.open(USER.toString());
    for (int week = 1; week <= 4; week++) {
      clock.advance(Duration.ofDays(6));
      session = service.refresh(refreshOf(session)).orElseThrow();
    }
    // 24 days in: the cookie lasts only the 6 days left, not a full idle week.
    assertThat(session.refreshMaxAge()).isEqualTo(Duration.ofDays(6));
    clock.advance(Duration.ofDays(6));

    String last = refreshOf(session);
    assertThatThrownBy(() -> service.refresh(last)).isInstanceOf(NoActiveSessionException.class);
  }

  @Test
  void aChangedPasswordOrRoleOrADisabledAccountEndsTheRefresh() {
    IssuedSession changed = service.open(USER.toString());
    version = 1;
    assertThatThrownBy(() -> service.refresh(refreshOf(changed)))
        .isInstanceOf(NoActiveSessionException.class);

    IssuedSession disabled = service.open(USER.toString());
    status = "INACTIVE";
    assertThatThrownBy(() -> service.refresh(refreshOf(disabled)))
        .isInstanceOf(NoActiveSessionException.class);
    assertThat(store.sessions()).allSatisfy(row -> assertThat(row.revokedAt).isNotNull());
  }

  @Test
  void aMalformedUnknownOrForeignTokenIsNoSession() {
    IssuedSession mine = service.open(USER.toString());
    IssuedSession other = service.open(USER.toString());
    String secretOfOther = refreshOf(other).substring(23);

    for (String token :
        new String[] {
          "",
          "garbage",
          refreshOf(mine) + "x",
          sidOf(mine) + "." + "A".repeat(43),
          // The secret of another session under my sid: the sid binds the token to its session.
          sidOf(mine) + "." + secretOfOther
        }) {
      assertThatThrownBy(() -> service.refresh(token)).isInstanceOf(NoActiveSessionException.class);
    }
    assertThat(store.sessions()).allSatisfy(row -> assertThat(row.revokedAt).isNull());
  }

  @Test
  void aTemporarySessionHasNoRefreshTokenUntilItChoosesItsPassword() {
    IssuedSession other = service.open(USER.toString());
    Instant expiresAt = NOW.plus(Duration.ofMinutes(30));
    IssuedSession temporary = service.openTemporary(USER.toString(), 9L, expiresAt);
    assertThat(temporary.refreshToken()).isNull();
    assertThat(temporary.refreshMaxAge()).isNull();
    assertThat(temporary.accessMaxAge())
        .as("its cookie lasts as its password")
        .isEqualTo(Duration.ofMinutes(30));
    String sid = temporary.accessToken().substring(temporary.accessToken().lastIndexOf(':') + 1);
    clock.advance(Duration.ofMinutes(5));
    version = 1;

    IssuedSession full = service.continueAfterPasswordChange(USER.toString(), sid);

    assertThat(sidOf(full)).as("the same session goes on (TAR-227)").isEqualTo(sid);
    assertThat(full.refreshToken()).startsWith(sid + ".");
    verify(signals).credentialsChanged(USER, version, SessionStorePort.hash(sid));
    var rows = store.sessions();
    assertThat(rows.get(1).temporary).isFalse();
    assertThat(rows.get(1).securityVersion).isEqualTo(1);
    assertThat(rows.get(1).absoluteExpiresAt).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
    assertThat(rows.get(0).revokedReason).isEqualTo(RevokedReason.PASSWORD_CHANGE);
    assertThat(service.refresh(refreshOf(full))).isPresent();
    assertThatThrownBy(() -> service.refresh(refreshOf(other)))
        .isInstanceOf(NoActiveSessionException.class);
  }

  @Test
  void logoutClosesTheSessionOfTheRefreshTokenOrElseOfTheAccessToken() {
    IssuedSession byRefresh = service.open(USER.toString());
    IssuedSession temporary =
        service.openTemporary(USER.toString(), 9L, NOW.plus(Duration.ofMinutes(30)));

    service.logout(refreshOf(byRefresh), "not-a-token");
    service.logout(null, temporary.accessToken());
    verify(signals).revokeSession(USER, SessionStorePort.hash(sidOf(byRefresh)));
    verify(signals).revokeSession(USER, SessionStorePort.hash(sidOf(temporary)));

    assertThat(store.sessions())
        .allSatisfy(row -> assertThat(row.revokedReason).isEqualTo(RevokedReason.LOGOUT));
    assertThatThrownBy(() -> service.refresh(refreshOf(byRefresh)))
        .isInstanceOf(NoActiveSessionException.class);
  }

  @Test
  void logoutWithoutAnyTokenOfASessionIsNoSession() {
    assertThatThrownBy(() -> service.logout(null, null))
        .isInstanceOf(NoActiveSessionException.class);
    assertThatThrownBy(() -> service.logout("garbage", "not-a-token"))
        .isInstanceOf(NoActiveSessionException.class);
  }
}
