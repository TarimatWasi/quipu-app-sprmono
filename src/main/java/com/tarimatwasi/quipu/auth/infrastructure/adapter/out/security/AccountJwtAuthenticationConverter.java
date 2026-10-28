package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security;

import com.tarimatwasi.quipu.auth.application.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.shared.access.AccessSessions;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.UnknownMaskedIdException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Turns a valid token into the session of its account. The token is only as good as the account
 * behind it, read on every request: the account must exist and not be disabled, and a password
 * change after the token was issued ends the session (TAR-125). The role and the pending password
 * change come from the account, not from the token, so a closed account, a changed password or a
 * new role take effect at once. The name of the authentication is the (unmasked) id of the user.
 */
@Component
public class AccountJwtAuthenticationConverter
    implements Converter<Jwt, AbstractOAuth2TokenAuthenticationToken<Jwt>> {

  /** The session may only change the password (RF-12); the HTTP rules look for this authority. */
  public static final String PASSWORD_CHANGE_PENDING = "PASSWORD_CHANGE_PENDING";

  private static final long MICROS_PER_SECOND = 1_000_000L;

  private final UserRepositoryPort users;
  private final IdMasker masker;

  public AccountJwtAuthenticationConverter(UserRepositoryPort users, IdMasker masker) {
    this.users = users;
    this.masker = masker;
  }

  @Override
  public AbstractOAuth2TokenAuthenticationToken<Jwt> convert(Jwt jwt) {
    if (jwt.getIssuedAt() == null) {
      throw invalid("The token has no issue time");
    }
    String subject = jwt.getSubject();
    if (subject == null) {
      throw invalid("The token has no subject");
    }
    Instant issuedAt = preciseIssuedAt(jwt);
    UserAccount account = accountOf(subject);
    if (account.isDisabled() || account.issuedBeforePasswordChange(issuedAt)) {
      throw invalid("The session is no longer valid");
    }
    List<GrantedAuthority> authorities = new ArrayList<>();
    authorities.add(new SimpleGrantedAuthority("ROLE_" + account.role().name()));
    boolean temporary = jwt.hasClaim(AccessSessions.TEMPORARY_CLAIM);
    if (account.mustChangePassword() || temporary) {
      authorities.add(new SimpleGrantedAuthority(PASSWORD_CHANGE_PENDING));
    }
    if (temporary) {
      authorities.add(new SimpleGrantedAuthority(AccessSessions.TEMPORARY_AUTHORITY));
    }
    return new JwtAuthenticationToken(jwt, authorities, account.id().toString());
  }

  /**
   * The issue time to compare with the password change (TAR-201). A token issued before the precise
   * claim existed has only the second, so it counts as issued at the last instant of that second:
   * the rule it was issued under, until it expires.
   *
   * <p>TAR-205: the precise claim must be a whole number of microseconds inside the second of
   * {@code iat}, both ends included (the issuer rounds the instant up, so the last nanoseconds of a
   * second give the first microsecond of the next); anything else, or a value that overflows, is an
   * invalid token.
   */
  private static Instant preciseIssuedAt(Jwt jwt) {
    Instant issuedAt = Objects.requireNonNull(jwt.getIssuedAt());
    Object claim = jwt.getClaim(JwtTokenProvider.ISSUED_AT_MICROS_CLAIM);
    if (claim == null) {
      return issuedAt.plusSeconds(1).minusNanos(1);
    }
    if (!(claim instanceof Long || claim instanceof Integer)) {
      throw invalid("The precise issue time is not a whole number");
    }
    long micros = ((Number) claim).longValue();
    try {
      long first = Math.multiplyExact(issuedAt.getEpochSecond(), MICROS_PER_SECOND);
      if (micros < first || micros > Math.addExact(first, MICROS_PER_SECOND)) {
        throw invalid("The precise issue time is outside the second of the token");
      }
    } catch (ArithmeticException overflow) {
      throw invalid("The issue time is out of range");
    }
    return Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
  }

  /** The subject is the masked id of the user; anything else is not a session of ours. */
  private UserAccount accountOf(String subject) {
    long id;
    try {
      id = masker.unmask(IdKind.USER, UUID.fromString(subject));
    } catch (IllegalArgumentException | UnknownMaskedIdException notAnId) {
      throw invalid("The subject is not a user id");
    }
    return users.findById(id).orElseThrow(() -> invalid("Unknown account"));
  }

  private static InvalidBearerTokenException invalid(String message) {
    return new InvalidBearerTokenException(message);
  }
}
