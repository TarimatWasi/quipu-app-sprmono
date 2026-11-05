package com.tarimatwasi.quipu.auth.application.port.in;

import org.jspecify.annotations.Nullable;

/** Reads the session of the person who is signed in, as the account is now. */
public interface CurrentSessionUseCase {

  /**
   * The account behind a session token, read from the database: a role change or a password change
   * made after the login shows up here even though the token still carries the old claims.
   *
   * @throws NoActiveSessionException the account no longer exists or is disabled
   */
  CurrentSession currentSession(String userId);

  /**
   * What the client needs to restore the session after a reload.
   *
   * @param displayName what the session shows: the full name of an administrator, or its email
   *     while it has none (TAR-188); a guest's comes from its own module
   */
  record CurrentSession(
      String role,
      @Nullable String displayName,
      @Nullable Long guestId,
      boolean mustChangePassword) {}
}
