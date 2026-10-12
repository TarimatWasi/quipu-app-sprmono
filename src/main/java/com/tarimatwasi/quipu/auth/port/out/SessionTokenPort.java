package com.tarimatwasi.quipu.auth.port.out;

/** Issues the session credential that the BFF hands to the browser. */
public interface SessionTokenPort {

  /**
   * Issues a session token for the account. The token carries only who the account is: the role and
   * the pending password change are read from the account on every request (BE-SPR-SEC-07).
   */
  String issue(String userId);
}
