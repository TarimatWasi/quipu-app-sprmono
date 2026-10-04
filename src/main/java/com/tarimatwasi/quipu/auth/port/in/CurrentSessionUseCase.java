package com.tarimatwasi.quipu.auth.port.in;

/** Reads the session of the person who is signed in, as the account is now. */
public interface CurrentSessionUseCase {

  /**
   * The account behind a session token, read from the database: a role change or a password change
   * made after the login shows up here even though the token still carries the old claims.
   *
   * @throws NoActiveSessionException the account no longer exists or is disabled
   */
  CurrentSession currentSession(String userId);

  /** What the client needs to restore the session after a reload. */
  record CurrentSession(String role, String displayEmail, boolean mustChangePassword) {}
}
