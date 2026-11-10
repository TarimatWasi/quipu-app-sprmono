package com.tarimatwasi.quipu.auth.application.port.in;

import java.util.Optional;

/** TAR-228: renews a session with its refresh token, which rotates in each use. */
public interface RefreshSessionUseCase {

  /**
   * A new access token and a new refresh token for the same session (its sid, and with it the ids
   * shown in it, stay). A refresh token rotated less than 30 seconds ago answers empty and changes
   * nothing: tabs that share the cookies raced, and the browser already holds the new ones.
   *
   * @throws NoActiveSessionException if the refresh token is unknown or malformed, its session is
   *     closed, idle for 7 days or past its 30 days, the account changed or is disabled, or the
   *     token was already rotated (a reuse, which closes the session)
   */
  Optional<IssuedSession> refresh(String refreshToken);
}
