package com.tarimatwasi.quipu.auth.application.port.in;

import org.jspecify.annotations.Nullable;

/** Authenticates a user by document and password. */
public interface LoginUseCase {

  /**
   * Checks the credentials.
   *
   * @throws com.tarimatwasi.quipu.auth.application.port.in.InvalidCredentialsException unknown
   *     document or
   *     <p>wrong password (the same error, to avoid revealing which)
   * @throws com.tarimatwasi.quipu.auth.application.port.in.AccountDisabledException the account is
   *     inactive
   * @throws TemporaryPasswordExpiredException the temporary password matched but expired
   */
  LoginResult login(LoginCommand command);

  /** The kinds of identity document a person signs in with. */
  enum DocumentKind {
    DNI,
    CE,
    PASSPORT
  }

  /** Credentials presented at login. */
  record LoginCommand(DocumentKind documentType, String documentNumber, String rawPassword) {}

  /**
   * Outcome of a successful login.
   *
   * @param role the name of the account role
   * @param sessionToken the signed session the caller puts in the cookie
   */
  record LoginResult(
      String userId,
      String role,
      @Nullable String displayEmail,
      @Nullable Long guestId,
      boolean mustChangePassword,
      String sessionToken) {}
}
