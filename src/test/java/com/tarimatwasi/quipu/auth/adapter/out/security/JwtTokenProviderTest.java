package com.tarimatwasi.quipu.auth.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

  private static final String SECRET = "test-secret-test-secret-test-secret-test-secret";

  private final UserRepositoryPort users = mock(UserRepositoryPort.class);
  private final JwtTokenProvider provider =
      new JwtTokenProvider(SECRET, 60, Clock.systemUTC(), users);

  @Test
  void parsesTokenItIssued() {
    String token = provider.issue("user-1", "ADMIN");

    assertThat(provider.parse(token))
        .hasValueSatisfying(
            s -> {
              assertThat(s.userId()).isEqualTo("user-1");
              assertThat(s.role()).isEqualTo("ADMIN");
            });
  }

  @Test
  void rejectsTokenSignedWithAnotherKey() {
    String forged =
        new JwtTokenProvider(
                "other-secret-other-secret-other-secret-other", 60, Clock.systemUTC(), users)
            .issue("user-1", "ADMIN");

    assertThat(provider.parse(forged)).isEmpty();
  }

  @Test
  void rejectsExpiredToken() {
    String expired =
        new JwtTokenProvider(SECRET, -1, Clock.systemUTC(), users).issue("user-1", "ADMIN");

    assertThat(provider.parse(expired)).isEmpty();
  }

  @Test
  @SuppressWarnings("NullAway") // intentional null: a signed token without that claim
  void rejectsSignedTokenWithoutRole() {
    assertThat(provider.parse(provider.issue("user-1", null))).isEmpty();
  }

  @Test
  @SuppressWarnings("NullAway") // intentional null: a signed token without that claim
  void rejectsSignedTokenWithoutSubject() {
    assertThat(provider.parse(provider.issue(null, "ADMIN"))).isEmpty();
  }

  @Test
  void rejectsGarbage() {
    assertThat(provider.parse("not-a-jwt")).isEmpty();
  }

  @Test
  void carriesThePendingPasswordChangeOfTheSession() {
    String token = provider.issue("user-1", "GUEST", true);

    assertThat(provider.parse(token))
        .hasValueSatisfying(s -> assertThat(s.mustChangePassword()).isTrue());
  }

  @Test
  void aTokenWithoutTheMarkHasNoPendingPasswordChange() {
    String token = provider.issue("user-1", "GUEST");

    assertThat(provider.parse(token))
        .hasValueSatisfying(s -> assertThat(s.mustChangePassword()).isFalse());
  }

  @Test
  void aFreshTokenNeverAsksTheDatabase() {
    provider.parse(provider.issue("user-1", "GUEST", false));
    provider.parse(provider.issue("user-1", "GUEST", true));

    verifyNoInteractions(users);
  }

  /** A token issued before the mark existed (rolling deployment) is decided by the account. */
  @Test
  void aTokenFromBeforeTheMarkTakesThePendingChangeFromTheDatabase() {
    UUID id = UUID.randomUUID();
    when(users.findById(id)).thenReturn(Optional.of(account(id, true)));

    assertThat(provider.parse(unmarkedToken(id.toString())))
        .hasValueSatisfying(s -> assertThat(s.mustChangePassword()).isTrue());
  }

  @Test
  void aTokenFromBeforeTheMarkOfAnAccountWithoutPendingChangeIsNotPending() {
    UUID id = UUID.randomUUID();
    when(users.findById(id)).thenReturn(Optional.of(account(id, false)));

    assertThat(provider.parse(unmarkedToken(id.toString())))
        .hasValueSatisfying(s -> assertThat(s.mustChangePassword()).isFalse());
  }

  @Test
  void aTokenFromBeforeTheMarkOfAnUnknownAccountIsRejected() {
    when(users.findById(any())).thenReturn(Optional.empty());

    assertThat(provider.parse(unmarkedToken(UUID.randomUUID().toString()))).isEmpty();
    assertThat(provider.parse(unmarkedToken("not-a-uuid"))).isEmpty();
  }

  private static String unmarkedToken(String subject) {
    return Jwts.builder()
        .subject(subject)
        .claim("role", "ADMIN")
        .expiration(Date.from(Instant.now().plusSeconds(600)))
        .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
        .compact();
  }

  private static UserAccount account(UUID id, boolean mustChangePassword) {
    return new UserAccount(
        id,
        "a@example.test",
        DocumentType.DNI,
        "12345678",
        "hash",
        Role.ADMIN,
        null,
        mustChangePassword,
        "ACTIVE",
        0,
        null);
  }
}
