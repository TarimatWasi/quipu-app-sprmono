package com.tarimatwasi.quipu.auth.domain.event;

/** Why a session ended before its time. */
public enum SessionEnd {
  /** The user logged out. */
  LOGOUT,
  /** A refresh token that was already rotated came back: the session is closed (theft). */
  REUSE,
  /** Another session of the account changed the password. */
  PASSWORD_CHANGE,
  /** The account changed (password, role, deactivation) or the session expired. */
  INVALIDATED
}
