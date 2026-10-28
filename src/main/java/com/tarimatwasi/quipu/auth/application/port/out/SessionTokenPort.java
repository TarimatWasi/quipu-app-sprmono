package com.tarimatwasi.quipu.auth.application.port.out;

import java.time.Instant;

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
   * Like {@link #issue} (a new session) but bound to the temporary password that opened it (its
   * access id, signed in the token) and ending when that password expires: only this session may
   * choose the password without giving the current one, and only while that same access is live
   * (RF-12).
   */
  String issueTemporary(String userId, Long grantId, Instant expiresAt);
}
