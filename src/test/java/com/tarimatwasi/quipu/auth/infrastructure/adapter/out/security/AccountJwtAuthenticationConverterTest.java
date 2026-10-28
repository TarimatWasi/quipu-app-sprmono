package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.application.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.shared.access.AccessSessions;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMaskProperties;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.TestIds;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * TAR-125 and TAR-164: a session token is only as good as the account behind it, read on every
 * request. The token is issued by {@link JwtTokenProvider}, read by Spring's JWT decoder and turned
 * into an authentication by {@link AccountJwtAuthenticationConverter}. TAR-227: every token names
 * its session, and the subject is masked with the key of that session.
 */
class AccountJwtAuthenticationConverterTest {

  private static final String SECRET = "test-secret-test-secret-test-secret-test-secret";
  private static final String OTHER_SECRET = "other-secret-other-secret-other-secret-other";
  private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

  private final UserRepositoryPort users = mock(UserRepositoryPort.class);
  private final MutableClock clock = new MutableClock(NOW);
  private static final String SESSION = "AAECAwQFBgcICQoLDA0ODw";
  private static final String OTHER_SESSION = "8PHy8_T19vf4-fr7_P3-_w";

  /** The issuer and the converter never mask through the request: they name the session. */
  private final IdMasker masker =
      new IdMasker(
          new IdMaskProperties("a-test-key-with-more-than-thirty-two-characters"), Optional::empty);

  private final MaskSession inSession = masker.session(SESSION);
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

    String token = provider.issue(id.toString());

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

    assertThat(authenticate(provider.issue(id.toString())))
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
    assertThat(authenticate(provider.issue(id.toString()))).isEmpty();

    Long disabled = TestIds.next();
    when(users.findById(disabled))
        .thenReturn(Optional.of(account(disabled, Role.ADMIN, "INACTIVE", false, null)));
    assertThat(authenticate(provider.issue(disabled.toString()))).isEmpty();

    assertThat(authenticate(signed(Map.of("sub", "not-a-uuid", "role", "ADMIN")))).isEmpty();
  }

  @Test
  void aTokenIssuedBeforeThePasswordWasChangedOpensNoSession() {
    String old = provider.issue(id.toString());
    clock.advance(Duration.ofSeconds(5));
    Instant changedAt = clock.instant();
    when(users.findById(id))
        .thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, changedAt)));

    assertThat(authenticate(old)).isEmpty();
  }

  /** TAR-201 (D-20): the second of the JWT is not enough; a token of that same second is ended. */
  @Test
  void aTokenIssuedInTheSameSecondButBeforeThePasswordChangeOpensNoSession() {
    clock.advance(Duration.ofMillis(100));
    String old = provider.issue(id.toString());
    Instant changedAt = NOW.plusMillis(900);
    when(users.findById(id))
        .thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, changedAt)));

    assertThat(authenticate(old)).isEmpty();
  }

  @Test
  void aTokenIssuedAtOrAfterThePasswordChangeStillWorks() {
    Instant changedAt = NOW.plusNanos(900_000_123);
    when(users.findById(id))
        .thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, changedAt)));

    // The change replaces the session cookie in the same request, with the clock read after it.
    clock.advance(Duration.between(NOW, changedAt));
    assertThat(authenticate(provider.issue(id.toString()))).isPresent();
    clock.advance(Duration.ofSeconds(30));
    assertThat(authenticate(provider.issue(id.toString()))).isPresent();
  }

  /**
   * Tokens issued before TAR-201 have only the second: they keep the old rule until they expire.
   */
  @Test
  void aTokenWithoutThePreciseIssueTimeIsJudgedByItsSecond() {
    Instant changedAt = NOW.plusMillis(900);
    when(users.findById(id))
        .thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, changedAt)));
    String sub = inSession.mask(IdKind.USER, id).toString();

    assertThat(authenticate(signed(Map.of("sub", sub)))).isPresent();
    when(users.findById(id))
        .thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, NOW.plusSeconds(1))));
    assertThat(authenticate(signed(Map.of("sub", sub)))).isEmpty();
  }

  @Test
  void anAccountThatNeverChangedItsPasswordAcceptsAnyValidToken() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));

    assertThat(authenticate(provider.issue(id.toString()))).isPresent();
  }

  @Test
  void aTokenSignedWithAnotherKeyIsRefused() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));
    String forged =
        providerFor(SessionJwt.key(OTHER_SECRET), Duration.ofMinutes(60)).issue(id.toString());

    assertThat(authenticate(forged)).isEmpty();
  }

  @Test
  void anExpiredTokenIsRefusedWithNoMarginAfterTheExpirationInstant() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));
    String token = providerFor(key, Duration.ofMinutes(1)).issue(id.toString());

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
                    Map.of("sub", inSession.mask(IdKind.USER, id).toString(), "role", "ADMIN"))))
        .isEmpty();
  }

  /** BE-SPR-SEC-07 (TAR-172): the token carries no role; the account decides. */
  @Test
  void aSignedTokenWithoutRoleOpensTheSessionOfItsAccount() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));

    assertThat(authenticate(signed(Map.of("sub", inSession.mask(IdKind.USER, id).toString()))))
        .hasValueSatisfying(
            auth ->
                assertThat(auth.getAuthorities())
                    .extracting("authority")
                    .containsExactly("ROLE_ADMIN"));
  }

  @Test
  void aSignedTokenWithoutSubjectIsRefused() {
    assertThat(authenticate(signed(Map.of("role", "ADMIN")))).isEmpty();
  }

  /** BE-SPR-SEC-07 (TAR-172): neither the role nor the pending-change mark travel in the token. */
  @Test
  void theIssuedTokenCarriesNoRoleOrPendingChangeClaim() {
    var claims = decoder.decode(provider.issue(id.toString())).getClaims();

    assertThat(claims).doesNotContainKeys("role", "mcp");
  }

  /**
   * The reason matters: a bad signature, an expired token and an account rule are different
   * failures.
   */
  @Test
  void eachKindOfRefusalFailsWhereItShould() {
    when(users.findById(id)).thenReturn(Optional.empty());
    String forged =
        providerFor(SessionJwt.key(OTHER_SECRET), Duration.ofMinutes(60)).issue(id.toString());
    String expired = providerFor(key, Duration.ofMinutes(1)).issue(id.toString());
    clock.advance(Duration.ofMinutes(2));

    // The decoder refuses what is not a valid token: signature and dates.
    assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(BadJwtException.class);
    assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(BadJwtException.class);
    // The converter refuses what is a valid token of an account that cannot have a session.
    String valid = provider.issue(id.toString());
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
        signed(Map.of("sub", inSession.mask(IdKind.USER, id).toString(), "role", "ADMIN"));

    assertThat(authenticate(unmarked))
        .hasValueSatisfying(
            auth ->
                assertThat(auth.getAuthorities())
                    .extracting("authority")
                    .contains(AccountJwtAuthenticationConverter.PASSWORD_CHANGE_PENDING));
  }

  /** The browser can read the token: it must not carry the sequential id of the account. */
  @Test
  void theSubjectOfTheTokenIsTheIdMaskedInItsSession() {
    Jwt token = decoder.decode(provider.issue(id.toString()));
    String subject = token.getSubject();

    assertThat(subject).isNotEqualTo(id.toString());
    assertThat(UUID.fromString(subject))
        .isEqualTo(masker.session(sessionOf(token)).mask(IdKind.USER, id));
  }

  /** TAR-227: each login or activation opens a session of its own, 128 random bits. */
  @Test
  void everyIssuedTokenNamesANewRandomSession() {
    var sessions = new HashSet<String>();
    for (int i = 0; i < 50; i++) {
      String sessionId = sessionOf(decoder.decode(provider.issue(id.toString())));
      assertThat(sessionId).matches("[A-Za-z0-9_-]{22}");
      assertThat(Base64.getUrlDecoder().decode(sessionId)).hasSize(16);
      sessions.add(sessionId);
    }
    sessions.add(
        sessionOf(decoder.decode(provider.issueTemporary(id.toString(), 7L, NOW.plusSeconds(60)))));

    assertThat(sessions).hasSize(51);
  }

  /** TAR-227: the same id masks differently in two sessions, the subject too. */
  @Test
  void twoSessionsOfOneAccountHaveDifferentSubjects() {
    String one = decoder.decode(provider.issue(id.toString())).getSubject();
    String two = decoder.decode(provider.issue(id.toString())).getSubject();

    assertThat(one).isNotEqualTo(two);
  }

  /** TAR-227: renewing a session (a password change) keeps its id and so its subject. */
  @Test
  void aRenewedTokenKeepsItsSession() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));
    Jwt first = decoder.decode(provider.issue(id.toString()));
    clock.advance(Duration.ofSeconds(3));

    Jwt renewed = decoder.decode(provider.renew(id.toString(), sessionOf(first)));

    assertThat(sessionOf(renewed)).isEqualTo(sessionOf(first));
    assertThat(renewed.getSubject()).isEqualTo(first.getSubject());
    assertThat(renewed.getIssuedAt()).isAfter(first.getIssuedAt());
    assertThat(authenticate(renewed.getTokenValue())).isPresent();
  }

  /** TAR-227 (D-75, 2a): a token from before the sessions names none: it is no session. */
  @Test
  void aTokenWithoutASessionIsRefused() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));
    String old = signedWithoutSession(Map.of("sub", inSession.mask(IdKind.USER, id).toString()));

    assertThat(authenticate(old)).isEmpty();
    Jwt decoded = decoder.decode(old);
    assertThatThrownBy(() -> converter.convert(decoded))
        .isInstanceOf(InvalidBearerTokenException.class)
        .hasMessage("The session is no longer valid");
  }

  /** TAR-227: a session id that is not one (not text, not 16 bytes) is no session either. */
  @Test
  void aTokenWithAMalformedSessionIsRefused() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));
    String sub = inSession.mask(IdKind.USER, id).toString();

    for (Object bad : List.<Object>of(42L, "", "short", SESSION + "AA", SESSION + "==")) {
      Map<String, Object> claims = new HashMap<>(Map.of("sub", sub));
      claims.put(AccessSessions.SESSION_ID_CLAIM, bad);
      Jwt decoded = decoder.decode(signed(claims));
      assertThatThrownBy(() -> converter.convert(decoded))
          .as(String.valueOf(bad))
          .isInstanceOf(InvalidBearerTokenException.class)
          .hasMessage("The session is no longer valid");
    }
  }

  /** TAR-227: a subject masked in one session does not open another, even of the same account. */
  @Test
  void aSubjectMaskedInAnotherSessionIsRefused() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));
    String sub = inSession.mask(IdKind.USER, id).toString();
    Map<String, Object> claims = new HashMap<>(Map.of("sub", sub));
    claims.put(AccessSessions.SESSION_ID_CLAIM, OTHER_SESSION);

    assertThat(authenticate(signed(Map.of("sub", sub)))).isPresent();
    assertThat(authenticate(signed(claims))).isEmpty();
  }

  /** TAR-227: the authentication carries the masking of its own session for the request. */
  @Test
  void theAuthenticationMasksWithTheKeyOfItsSession() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));
    Jwt token = decoder.decode(provider.issue(id.toString()));

    var auth = converter.convert(token);

    assertThat(auth).isInstanceOf(MaskedSessionAuthenticationToken.class);
    MaskSession session = ((MaskedSessionAuthenticationToken) auth).maskSession().orElseThrow();
    assertThat(session.mask(IdKind.GUEST, 5))
        .isEqualTo(masker.session(sessionOf(token)).mask(IdKind.GUEST, 5))
        .isNotEqualTo(inSession.mask(IdKind.GUEST, 5));
  }

  private static String sessionOf(Jwt token) {
    return java.util.Objects.requireNonNull(
        token.getClaimAsString(AccessSessions.SESSION_ID_CLAIM));
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
                signed(
                    Map.of("sub", inSession.mask(IdKind.GUEST, id).toString(), "role", "ADMIN"))))
        .isEmpty();
  }

  /**
   * TAR-205: the precise issue time must fall in the second of {@code iat}, in microseconds; a
   * value out of it, not a whole number, or one that overflows is an invalid token, never a 500.
   * The last microsecond of the second is in: the issuer rounds the instant up.
   */
  @Test
  void aPreciseIssueTimeOutsideTheSecondOfIatIsAnInvalidToken() {
    when(users.findById(id)).thenReturn(Optional.of(account(Role.ADMIN, "ACTIVE", false, null)));
    long iatMicros = NOW.getEpochSecond() * 1_000_000L;

    for (Object good : List.<Object>of(iatMicros, iatMicros + 999_999L, iatMicros + 1_000_000L)) {
      assertThat(converter.convert(jwt(NOW, good))).as(String.valueOf(good)).isNotNull();
    }
    for (Object bad :
        List.<Object>of(
            iatMicros - 1,
            iatMicros + 1_000_001L,
            Long.MAX_VALUE,
            Long.MIN_VALUE,
            1.5e300,
            (double) iatMicros,
            "1")) {
      assertThatThrownBy(() -> converter.convert(jwt(NOW, bad)))
          .as(String.valueOf(bad))
          .isInstanceOf(InvalidBearerTokenException.class);
    }
    // An iat so far ahead that it overflows in microseconds.
    assertThatThrownBy(() -> converter.convert(jwt(Instant.MAX, 0L)))
        .isInstanceOf(InvalidBearerTokenException.class);
  }

  private Jwt jwt(Instant issuedAt, Object preciseIssuedAt) {
    return Jwt.withTokenValue("t")
        .header("alg", "HS256")
        .subject(inSession.mask(IdKind.USER, id).toString())
        .issuedAt(issuedAt)
        .claim(AccessSessions.SESSION_ID_CLAIM, SESSION)
        .claim(JwtTokenProvider.ISSUED_AT_MICROS_CLAIM, preciseIssuedAt)
        .build();
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

  /** Like {@link #signed} but naming no session, as a token issued before TAR-227. */
  private String signedWithoutSession(Map<String, Object> claims) {
    JwtClaimsSet.Builder builder =
        JwtClaimsSet.builder().issuedAt(NOW).expiresAt(NOW.plusSeconds(600));
    claims.forEach(builder::claim);
    return encoder
        .encode(
            JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), builder.build()))
        .getTokenValue();
  }

  /** Names {@link #SESSION} unless the claims name another. */
  private String encode(Map<String, Object> claims, @Nullable Instant expiresAt) {
    JwtClaimsSet.Builder builder =
        JwtClaimsSet.builder().issuedAt(NOW).claim(AccessSessions.SESSION_ID_CLAIM, SESSION);
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
