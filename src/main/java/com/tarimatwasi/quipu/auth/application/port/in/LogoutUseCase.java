package com.tarimatwasi.quipu.auth.application.port.in;

import org.jspecify.annotations.Nullable;

/** TAR-228: closes one session on the server; the other sessions of the account stay open. */
public interface LogoutUseCase {

  /**
   * Closes the session that the refresh token names or, without one (a temporary session has none),
   * the one of the access token. From then on both its tokens are refused.
   *
   * @throws NoActiveSessionException if neither token names a session
   */
  void logout(@Nullable String refreshToken, @Nullable String accessToken);
}
