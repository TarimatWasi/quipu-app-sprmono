package com.tarimatwasi.quipu.shared.access;

/**
 * The session opened with a temporary password (RF-12): only that session may choose the password
 * without the current one, and only while the temporary password is live. Earlier sessions of the
 * same account keep asking for the current password.
 */
public final class AccessSessions {

  /** JWT claim set to true on the session opened with a temporary password. */
  public static final String TEMPORARY_CLAIM = "tmp";

  /** Authority the security layer gives to that session. */
  public static final String TEMPORARY_AUTHORITY = "TEMPORARY_SESSION";

  private AccessSessions() {}
}
