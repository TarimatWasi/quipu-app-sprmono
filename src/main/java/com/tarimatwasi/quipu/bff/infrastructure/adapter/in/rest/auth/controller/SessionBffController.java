package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.auth.controller;

import com.tarimatwasi.quipu.auth.application.port.in.LogoutUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.NoActiveSessionException;
import com.tarimatwasi.quipu.auth.application.port.in.RefreshSessionUseCase;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.auth.support.SessionCookies;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TAR-228: the end of the life of a session, after its login: its renewal with the refresh token
 * and its closing. Both find the session by its refresh token, so they work with an expired access
 * token; they are public routes that still need the CSRF pair.
 */
@RestController
public class SessionBffController {

  private final RefreshSessionUseCase refreshSessionUseCase;
  private final LogoutUseCase logoutUseCase;
  private final SessionCookies sessionCookies;

  public SessionBffController(
      RefreshSessionUseCase refreshSessionUseCase,
      LogoutUseCase logoutUseCase,
      SessionCookies sessionCookies) {
    this.refreshSessionUseCase = refreshSessionUseCase;
    this.logoutUseCase = logoutUseCase;
    this.sessionCookies = sessionCookies;
  }

  /**
   * TAR-228: renews the session with the refresh token, which rotates; it needs no live access
   * token, only the CSRF pair. Inside the race window of a rotation it answers 204 and sets
   * nothing.
   */
  @PostMapping("/bff/auth/refresh")
  public ResponseEntity<Void> refresh(
      @CookieValue(name = SessionCookies.REFRESH_NAME, required = false)
          @Nullable String refreshToken,
      HttpServletResponse response) {
    if (refreshToken == null) {
      throw new NoActiveSessionException();
    }
    refreshSessionUseCase
        .refresh(refreshToken)
        .ifPresent(session -> sessionCookies.writeSession(session, response));
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  /**
   * TAR-300, TAR-228: logout closes this session on the server (its access token is refused from
   * then on) and expires the session, refresh and CSRF cookies. The session is found by the refresh
   * token, so it works with an expired access token; other sessions of the account stay open. Needs
   * a valid CSRF token, as the contract says.
   */
  @PostMapping("/bff/auth/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(name = SessionCookies.REFRESH_NAME, required = false)
          @Nullable String refreshToken,
      @CookieValue(name = SessionCookies.SESSION_NAME, required = false)
          @Nullable String accessToken,
      HttpServletRequest request,
      HttpServletResponse response) {
    logoutUseCase.logout(refreshToken, accessToken);
    sessionCookies.clearCsrf(request, response);
    sessionCookies.clearSession(response);
    return ResponseEntity.noContent().build();
  }
}
