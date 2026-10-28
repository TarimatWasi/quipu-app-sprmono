package com.tarimatwasi.quipu.support;

import com.nimbusds.jwt.SignedJWT;
import com.tarimatwasi.quipu.shared.access.AccessSessions;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import jakarta.servlet.http.Cookie;
import java.security.SecureRandom;
import java.text.ParseException;
import java.util.Base64;
import java.util.Objects;

/**
 * TAR-227: a masked id is valid only in the session it was shown in. A test that builds a URL or
 * checks a body outside the request masks with the session of the token it sends.
 */
public final class SessionIds {

  private static final SecureRandom RANDOM = new SecureRandom();

  private SessionIds() {}

  /**
   * A fresh session id, for a test whose several accounts share one session so that one masking
   * serves all of its requests (the tokens are issued with {@code JwtTokenProvider#renew}).
   */
  public static String newSessionId() {
    byte[] raw = new byte[IdMasker.SESSION_ID_BYTES];
    RANDOM.nextBytes(raw);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  /** The masking of the session of this session cookie. */
  public static MaskSession of(IdMasker masker, Cookie session) {
    return of(masker, session.getValue());
  }

  /** The masking of the session of this token. */
  public static MaskSession of(IdMasker masker, String token) {
    return masker.session(sessionIdOf(token));
  }

  /** The session id of a token, read without checking it (the service checks it). */
  public static String sessionIdOf(String token) {
    try {
      return Objects.requireNonNull(
          SignedJWT.parse(token).getJWTClaimsSet().getStringClaim(AccessSessions.SESSION_ID_CLAIM),
          "the token names no session");
    } catch (ParseException notAToken) {
      throw new IllegalArgumentException("Not a session token", notAToken);
    }
  }
}
