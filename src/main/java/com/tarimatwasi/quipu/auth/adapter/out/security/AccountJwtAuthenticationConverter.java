package com.tarimatwasi.quipu.auth.adapter.out.security;

import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.UnknownMaskedIdException;
import java.util.ArrayList;
import java.util.List;
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

  private final UserRepositoryPort users;
  private final IdMasker masker;

  public AccountJwtAuthenticationConverter(UserRepositoryPort users, IdMasker masker) {
    this.users = users;
    this.masker = masker;
  }

  @Override
  public AbstractOAuth2TokenAuthenticationToken<Jwt> convert(Jwt jwt) {
    String role = jwt.getClaimAsString("role");
    if (role == null || role.isBlank() || jwt.getIssuedAt() == null) {
      throw invalid("The token has no role or issue time");
    }
    String subject = jwt.getSubject();
    if (subject == null) {
      throw invalid("The token has no subject");
    }
    UserAccount account = accountOf(subject);
    if (account.isDisabled() || account.issuedBeforePasswordChange(jwt.getIssuedAt())) {
      throw invalid("The session is no longer valid");
    }
    List<GrantedAuthority> authorities = new ArrayList<>();
    authorities.add(new SimpleGrantedAuthority("ROLE_" + account.role().name()));
    if (account.mustChangePassword()) {
      authorities.add(new SimpleGrantedAuthority(PASSWORD_CHANGE_PENDING));
    }
    return new JwtAuthenticationToken(jwt, authorities, account.id().toString());
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
