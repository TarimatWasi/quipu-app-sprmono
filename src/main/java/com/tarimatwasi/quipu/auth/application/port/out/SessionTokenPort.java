package com.tarimatwasi.quipu.auth.application.port.out;

import java.time.Instant;
import java.util.Optional;

/** Issues the session credential that the BFF hands to the browser. */
public interface SessionTokenPort {

  /**
   * Opens a new session of the account and issues its token. The token carries only who the account
   * is and which session it is: the role and the pending password change are read from the account
   * on every request (BE-SPR-SEC-07).
   */
  String issue(String userId);

  /**
   * TAR-227: a new token for a session already open, which keeps its id (and so the ids shown in
   * it). Like {@link #issue} in everything else.
   *
   * @param sessionId the id of the session, from its current token
   */
  String renew(String userId, String sessionId);

  /**
   * Like {@link #renew} but bound to the temporary password that opened the session (its access id,
   * signed in the token) and ending when that password expires: only this session may choose the
   * password without giving the current one, and only while that same access is live (RF-12).
   *
   * @param sessionId the id of the session, chosen when it opened (TAR-228)
   */
  String issueTemporary(String userId, String sessionId, Long grantId, Instant expiresAt);

  /**
   * TAR-228: the session an access token names, if its signature is ours and it has not expired.
   * Logout uses it for a session without a refresh token (a temporary one).
   */
  Optional<String> sessionIdOf(String accessToken);
}
