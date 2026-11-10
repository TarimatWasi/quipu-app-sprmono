package com.tarimatwasi.quipu.auth.application.port.in;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * TAR-228: what a login, an activation, a password change or a refresh hands to the browser.
 *
 * @param accessToken the access token (a JWT of 15 minutes)
 * @param refreshToken the refresh token; null for a temporary session (RF-12), which has none until
 *     it chooses its password
 * @param refreshMaxAge how long the browser keeps the refresh token: the idle expiry, never past
 *     the absolute one; null with no refresh token
 */
public record IssuedSession(
    String accessToken, @Nullable String refreshToken, @Nullable Duration refreshMaxAge) {

  /** Neither token is printed: a record that reaches a log must not carry them. */
  @Override
  public String toString() {
    return "IssuedSession[accessToken=***, refreshToken="
        + (refreshToken == null ? "null" : "***")
        + ", refreshMaxAge="
        + refreshMaxAge
        + "]";
  }
}
