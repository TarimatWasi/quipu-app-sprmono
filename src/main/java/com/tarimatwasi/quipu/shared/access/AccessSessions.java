package com.tarimatwasi.quipu.shared.access;

/**
 * The session opened with a temporary password (RF-12): only that session may choose the password
 * without the current one, and only while the temporary password is live. Earlier sessions of the
 * same account keep asking for the current password.
 */
public final class AccessSessions {

  /** JWT claim with the id of the access (temporary password) that opened the session. */
  public static final String TEMPORARY_CLAIM = "tmp";

  /**
   * TAR-227: JWT claim with the id of the session, 128 random bits in Base64Url. It is chosen when
   * the session opens (login, activation) and kept by every token that renews it (a password
   * change), so it names the session and, with it, the key of the ids shown in it. A token without
   * it is not a session.
   */
  public static final String SESSION_ID_CLAIM = "sid";

  /** Authority the security layer gives to that session. */
  public static final String TEMPORARY_AUTHORITY = "TEMPORARY_SESSION";

  private AccessSessions() {}
}
