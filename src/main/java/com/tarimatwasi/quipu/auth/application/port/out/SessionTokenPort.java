package com.tarimatwasi.quipu.auth.application.port.out;

import java.time.Instant;

/** Issues the session credential that the BFF hands to the browser. */
public interface SessionTokenPort {

  /**
   * Issues a session token for the account. The token carries only who the account is: the role and
   * the pending password change are read from the account on every request (BE-SPR-SEC-07).
   */
  String issue(String userId);

  /**
   * Like {@link #issue} but bound to the temporary password that opened it (its access id, signed
   * in the token) and ending when that password expires: only this session may choose the password
   * without giving the current one, and only while that same access is live (RF-12).
   */
  String issueTemporary(String userId, Long grantId, Instant expiresAt);
}
