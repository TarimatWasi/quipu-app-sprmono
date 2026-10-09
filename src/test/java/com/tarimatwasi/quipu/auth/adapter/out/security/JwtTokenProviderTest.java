package com.tarimatwasi.quipu.auth.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.TestIds;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** TAR-125: a token is only as good as the account behind it, read on every request. */
class JwtTokenProviderTest {

  private static final String SECRET = "test-secret-test-secret-test-secret-test-secret";
  private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

  private final UserRepositoryPort users = mock(UserRepositoryPort.class);
  private final MutableClock clock = new MutableClock(NOW);
  private final JwtTokenProvider provider = providerFor(SECRET, Duration.ofMinutes(60), clock);
  private final Long id = TestIds.next();

  private JwtTokenProvider providerFor(String secret, Duration expiration, Clock clock) {
    return new JwtTokenProvider(new JwtProperties(secret, expiration), clock, users);
  }

  @Test
  void parsesTokenOfAnActiveAccountAndTakesTheSessionFromTheAccount() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));

    String token = provider.issue(id.toString(), "GUEST", true);

    assertThat(provider.parse(token))
        .hasValueSatisfying(
            s -> {
              assertThat(s.userId()).isEqualTo(id.toString());
              // The account decides, not the claims of the token (a promoted or demoted role, a
              // change of password made elsewhere).
              assertThat(s.role()).isEqualTo("ADMIN");
              assertThat(s.mustChangePassword()).isFalse();
            });
  }

  @Test
  void theSessionMustChangePasswordWhileTheAccountDoes() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.GUEST, "ACTIVE", true, null)));

    assertThat(provider.parse(provider.issue(id.toString(), "GUEST", false)))
        .hasValueSatisfying(s -> assertThat(s.mustChangePassword()).isTrue());
  }

  @Test
  void rejectsTokenOfAnUnknownDisabledOrMalformedAccount() {
    when(users.findById(id)).thenReturn(Optional.empty());
    assertThat(provider.parse(provider.issue(id.toString(), "ADMIN"))).isEmpty();

    Long disabled = TestIds.next();
    when(users.findById(disabled))
        .thenReturn(Optional.of(account(disabled, Role.ADMIN, "INACTIVE", false, null)));
    assertThat(provider.parse(provider.issue(disabled.toString(), "ADMIN"))).isEmpty();

    assertThat(provider.parse(provider.issue("not-a-uuid", "ADMIN"))).isEmpty();
  }

  @Test
  void rejectsATokenIssuedBeforeThePasswordWasChanged() {
    String old = provider.issue(id.toString(), "ADMIN");
    clock.advance(Duration.ofSeconds(5));
    Instant changedAt = clock.instant();
    when(users.findById(id))
        .thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, changedAt)));

    assertThat(provider.parse(old)).isEmpty();
  }

  @Test
  void keepsATokenIssuedInTheSameSecondOrAfterThePasswordChange() {
    Instant changedAt = NOW.plusMillis(900);
    when(users.findById(id))
        .thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, changedAt)));

    // The change replaces the session cookie in the same request: the new token carries the
    // second of the change itself.
    assertThat(provider.parse(provider.issue(id.toString(), "ADMIN"))).isPresent();
    clock.advance(Duration.ofSeconds(30));
    assertThat(provider.parse(provider.issue(id.toString(), "ADMIN"))).isPresent();
  }

  @Test
  void anAccountThatNeverChangedItsPasswordAcceptsAnyValidToken() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));

    assertThat(provider.parse(provider.issue(id.toString(), "ADMIN"))).isPresent();
  }

  @Test
  void rejectsTokenSignedWithAnotherKey() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));
    String forged =
        providerFor(
                "other-secret-other-secret-other-secret-other",
                Duration.ofMinutes(60),
                Clock.systemUTC())
            .issue(id.toString(), "ADMIN");

    assertThat(provider.parse(forged)).isEmpty();
  }

  @Test
  void rejectsExpiredToken() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));
    // Same clock as the provider that parses: a token issued against the real clock stops being
    // expired once the real time passes the fixed NOW of this test.
    String expired =
        providerFor(SECRET, Duration.ofMinutes(1), clock).issue(id.toString(), "ADMIN");
    clock.advance(Duration.ofMinutes(2));

    assertThat(provider.parse(expired)).isEmpty();
  }

  @Test
  @SuppressWarnings(
      "NullAway") // BE-SPR-NUL-02 TAR-148: intentional null, a signed token without that claim
  void rejectsSignedTokenWithoutRole() {
    assertThat(provider.parse(provider.issue(id.toString(), null))).isEmpty();
  }

  @Test
  @SuppressWarnings(
      "NullAway") // BE-SPR-NUL-02 TAR-148: intentional null, a signed token without that claim
  void rejectsSignedTokenWithoutSubject() {
    assertThat(provider.parse(provider.issue(null, "ADMIN"))).isEmpty();
  }

  @Test
  void rejectsGarbage() {
    assertThat(provider.parse("not-a-jwt")).isEmpty();
  }

  /** A token from before the marks existed (no mcp claim) is judged like any other. */
  @Test
  void aTokenWithoutThePendingChangeClaimIsJudgedByTheAccountToo() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", true, null)));

    String unmarked =
        Jwts.builder()
            .subject(id.toString())
            .claim("role", "ADMIN")
            .issuedAt(Date.from(NOW))
            .expiration(Date.from(NOW.plusSeconds(600)))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
            .compact();

    assertThat(provider.parse(unmarked))
        .hasValueSatisfying(s -> assertThat(s.mustChangePassword()).isTrue());
  }

  private UserAccount account(
      Role role, String status, boolean mustChange, @Nullable Instant passwordChangedAt) {
    return account(id, role, status, mustChange, passwordChangedAt);
  }

  private static UserAccount account(
      Long id, Role role, String status, boolean mustChange, @Nullable Instant passwordChangedAt) {
    return new UserAccount(
        id,
        "a@example.test",
        DocumentType.DNI,
        "12345678",
        "hash",
        role,
        null,
        mustChange,
        status,
        0,
        null,
        passwordChangedAt);
  }
}
