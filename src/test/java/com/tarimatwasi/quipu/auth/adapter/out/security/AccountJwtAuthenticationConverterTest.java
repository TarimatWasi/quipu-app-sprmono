package com.tarimatwasi.quipu.auth.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMaskProperties;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.TestIds;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * TAR-125 and TAR-164: a session token is only as good as the account behind it, read on every
 * request. The token is issued by {@link JwtTokenProvider}, read by Spring's JWT decoder and turned
 * into an authentication by {@link AccountJwtAuthenticationConverter}.
 */
class AccountJwtAuthenticationConverterTest {

  private static final String SECRET = "test-secret-test-secret-test-secret-test-secret";
  private static final String OTHER_SECRET = "other-secret-other-secret-other-secret-other";
  private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

  private final UserRepositoryPort users = mock(UserRepositoryPort.class);
  private final MutableClock clock = new MutableClock(NOW);
  private final IdMasker masker =
      new IdMasker(new IdMaskProperties("a-test-key-with-more-than-thirty-two-characters"));
  private final SecretKey key = SessionJwt.key(SECRET);
  private final JwtDecoder decoder = SessionJwt.decoder(key, clock);
  private final JwtEncoder encoder = SessionJwt.encoder(key);
  private final JwtTokenProvider provider = providerFor(key, Duration.ofMinutes(60));
  private final AccountJwtAuthenticationConverter converter =
      new AccountJwtAuthenticationConverter(users, masker);
  private final Long id = TestIds.next();

  private JwtTokenProvider providerFor(SecretKey signingKey, Duration expiration) {
    return new JwtTokenProvider(
        new JwtProperties(SECRET, expiration), clock, SessionJwt.encoder(signingKey), masker);
  }

  /** What the filter chain does with a cookie: decode it, then build the authentication. */
  private Optional<Authentication> authenticate(String token) {
    try {
      return Optional.ofNullable(converter.convert(decoder.decode(token)));
    } catch (JwtException | AuthenticationException refused) {
      return Optional.empty();
    }
  }

  @Test
  void anActiveAccountOpensASessionAndTheAccountDecidesTheRole() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));

    String token = provider.issue(id.toString(), "GUEST", true);

    assertThat(authenticate(token))
        .hasValueSatisfying(
            auth -> {
              assertThat(auth.getName()).isEqualTo(id.toString());
              // The account decides, not the claims of the token (a promoted or demoted role, a
              // change of password made elsewhere).
              assertThat(auth.getAuthorities())
                  .extracting("authority")
                  .containsExactly("ROLE_ADMIN");
            });
  }

  @Test
  void theSessionMustChangePasswordWhileTheAccountDoes() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.GUEST, "ACTIVE", true, null)));

    assertThat(authenticate(provider.issue(id.toString(), "GUEST", false)))
        .hasValueSatisfying(
            auth ->
                assertThat(auth.getAuthorities())
                    .extracting("authority")
                    .containsExactlyInAnyOrder(
                        "ROLE_GUEST", AccountJwtAuthenticationConverter.PASSWORD_CHANGE_PENDING));
  }

  @Test
  void anUnknownDisabledOrMalformedAccountOpensNoSession() {
    when(users.findById(id)).thenReturn(Optional.empty());
    assertThat(authenticate(provider.issue(id.toString(), "ADMIN"))).isEmpty();

    Long disabled = TestIds.next();
    when(users.findById(disabled))
        .thenReturn(Optional.of(account(disabled, Role.ADMIN, "INACTIVE", false, null)));
    assertThat(authenticate(provider.issue(disabled.toString(), "ADMIN"))).isEmpty();

    assertThat(authenticate(signed(Map.of("sub", "not-a-uuid", "role", "ADMIN")))).isEmpty();
  }

  @Test
  void aTokenIssuedBeforeThePasswordWasChangedOpensNoSession() {
    String old = provider.issue(id.toString(), "ADMIN");
    clock.advance(Duration.ofSeconds(5));
    Instant changedAt = clock.instant();
    when(users.findById(id))
        .thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, changedAt)));

    assertThat(authenticate(old)).isEmpty();
  }

  @Test
  void aTokenIssuedInTheSameSecondOrAfterThePasswordChangeStillWorks() {
    Instant changedAt = NOW.plusMillis(900);
    when(users.findById(id))
        .thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, changedAt)));

    // The change replaces the session cookie in the same request: the new token carries the
    // second of the change itself.
    assertThat(authenticate(provider.issue(id.toString(), "ADMIN"))).isPresent();
    clock.advance(Duration.ofSeconds(30));
    assertThat(authenticate(provider.issue(id.toString(), "ADMIN"))).isPresent();
  }

  @Test
  void anAccountThatNeverChangedItsPasswordAcceptsAnyValidToken() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));

    assertThat(authenticate(provider.issue(id.toString(), "ADMIN"))).isPresent();
  }

  @Test
  void aTokenSignedWithAnotherKeyIsRefused() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));
    String forged =
        providerFor(SessionJwt.key(OTHER_SECRET), Duration.ofMinutes(60))
            .issue(id.toString(), "ADMIN");

    assertThat(authenticate(forged)).isEmpty();
  }

  @Test
  void anExpiredTokenIsRefusedWithNoMarginAfterTheExpirationInstant() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));
    String token = providerFor(key, Duration.ofMinutes(1)).issue(id.toString(), "ADMIN");

    clock.advance(Duration.ofSeconds(59));
    assertThat(authenticate(token)).isPresent();
    clock.advance(Duration.ofSeconds(2));
    assertThat(authenticate(token)).isEmpty();
  }

  @Test
  void aTokenWithoutExpirationIsRefused() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));

    assertThat(
            authenticate(
                signedWithoutExpiration(
                    Map.of("sub", masker.mask(IdKind.USER, id).toString(), "role", "ADMIN"))))
        .isEmpty();
  }

  @Test
  void aSignedTokenWithoutRoleOrSubjectIsRefused() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));
    String masked = masker.mask(IdKind.USER, id).toString();

    assertThat(authenticate(signed(Map.of("sub", masked)))).isEmpty();
    assertThat(authenticate(signed(Map.of("role", "ADMIN")))).isEmpty();
  }

  /**
   * The reason matters: a bad signature, an expired token and an account rule are different
   * failures.
   */
  @Test
  void eachKindOfRefusalFailsWhereItShould() {
    when(users.findById(id)).thenReturn(Optional.empty());
    String forged =
        providerFor(SessionJwt.key(OTHER_SECRET), Duration.ofMinutes(60))
            .issue(id.toString(), "ADMIN");
    String expired = providerFor(key, Duration.ofMinutes(1)).issue(id.toString(), "ADMIN");
    clock.advance(Duration.ofMinutes(2));

    // The decoder refuses what is not a valid token: signature and dates.
    assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(BadJwtException.class);
    assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(BadJwtException.class);
    // The converter refuses what is a valid token of an account that cannot have a session.
    String valid = provider.issue(id.toString(), "ADMIN");
    assertThatThrownBy(() -> converter.convert(decoder.decode(valid)))
        .isInstanceOf(InvalidBearerTokenException.class);
  }

  @Test
  void garbageIsRefused() {
    assertThat(authenticate("not-a-jwt")).isEmpty();
  }

  /** A token from before the marks existed (no mcp claim) is judged like any other. */
  @Test
  void aTokenWithoutThePendingChangeClaimIsJudgedByTheAccountToo() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", true, null)));
    String unmarked =
        signed(Map.of("sub", masker.mask(IdKind.USER, id).toString(), "role", "ADMIN"));

    assertThat(authenticate(unmarked))
        .hasValueSatisfying(
            auth ->
                assertThat(auth.getAuthorities())
                    .extracting("authority")
                    .contains(AccountJwtAuthenticationConverter.PASSWORD_CHANGE_PENDING));
  }

  /** The browser can read the token: it must not carry the sequential id of the account. */
  @Test
  void theSubjectOfTheTokenIsAMaskedIdAndNotTheNumericOne() {
    String subject = decoder.decode(provider.issue(id.toString(), "ADMIN")).getSubject();

    assertThat(subject).isNotEqualTo(id.toString());
    assertThat(UUID.fromString(subject)).isEqualTo(masker.mask(IdKind.USER, id));
  }

  /** A token from before the masking carried the numeric id: it no longer opens a session. */
  @Test
  void aTokenWhoseSubjectIsTheNumericIdIsRefused() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));

    assertThat(authenticate(signed(Map.of("sub", id.toString(), "role", "ADMIN")))).isEmpty();
  }

  /** A masked id issued for another kind of resource is not a user. */
  @Test
  void aTokenWhoseSubjectIsAnotherKindOfIdIsRefused() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));

    assertThat(
            authenticate(
                signed(Map.of("sub", masker.mask(IdKind.GUEST, id).toString(), "role", "ADMIN"))))
        .isEmpty();
  }

  /**
   * A token signed with the real key and the claims the test says, issued now and valid 10 minutes.
   */
  private String signed(Map<String, Object> claims) {
    return encode(claims, NOW.plusSeconds(600));
  }

  private String signedWithoutExpiration(Map<String, Object> claims) {
    return encode(claims, null);
  }

  private String encode(Map<String, Object> claims, @Nullable Instant expiresAt) {
    JwtClaimsSet.Builder builder = JwtClaimsSet.builder().issuedAt(NOW);
    if (expiresAt != null) {
      builder.expiresAt(expiresAt);
    }
    claims.forEach(builder::claim);
    return encoder
        .encode(
            JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), builder.build()))
        .getTokenValue();
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
