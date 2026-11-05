package com.tarimatwasi.quipu.auth.application.port.in;

import org.jspecify.annotations.Nullable;

/** RF-16: self-service password recovery with a single-use code sent by email. */
public interface PasswordRecoveryUseCase {

  /**
   * Sends a recovery link to the email when it belongs to an active account. It answers the same
   * way for any other email, so the answer never reveals whether an account exists. Requests for
   * the same account less than a minute apart send only one email.
   */
  void requestReset(String email);

  /**
   * Sets a new password with the code from the email and invalidates the code. An administrator
   * whose account has no name yet (the initial one, ADR-F2, or one created by another, RF-22)
   * activates it this way and writes its full name (A0.5, TAR-127, TAR-188); for any account that
   * already has one, or a guest, the name is ignored.
   *
   * @param name the full name, already stripped and within the limits of the contract; may be null
   * @throws InvalidResetCodeException if the code is unknown, expired, already used, or belongs to
   *     a disabled account (one answer for all of them)
   * @throws WeakPasswordException if the new password does not meet the policy; the code is not
   *     consumed
   * @throws AdministratorNameRequiredException if the account is an administrator without name and
   *     no name came; the code is not consumed
   */
  void resetPassword(String code, String newPassword, @Nullable String name);
}
