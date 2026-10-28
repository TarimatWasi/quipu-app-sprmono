package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security;

import com.tarimatwasi.quipu.shared.masking.MaskSession;
import java.util.Collection;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * TAR-227: the session of a valid token together with the masking of that session, derived once
 * when the token was read (the subject needed it). It lives only as long as the request, so the
 * derived key is kept nowhere else. The key is never serialized: a deserialized copy has none, and
 * masking then fails closed.
 */
public final class MaskedSessionAuthenticationToken extends JwtAuthenticationToken {

  private static final long serialVersionUID = 1L;

  private final transient @Nullable MaskSession maskSession;

  MaskedSessionAuthenticationToken(
      Jwt jwt,
      Collection<? extends GrantedAuthority> authorities,
      String name,
      MaskSession maskSession) {
    super(jwt, authorities, name);
    this.maskSession = maskSession;
  }

  /** The masking of this session; empty only in a deserialized copy. */
  public Optional<MaskSession> maskSession() {
    return Optional.ofNullable(maskSession);
  }

  /** The masking follows from the token, so two sessions are equal as their tokens are. */
  @Override
  public boolean equals(@Nullable Object obj) {
    return obj instanceof MaskedSessionAuthenticationToken && super.equals(obj);
  }

  /** As {@link #equals}: the token decides. */
  @Override
  public int hashCode() {
    return super.hashCode();
  }
}
