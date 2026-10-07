package com.tarimatwasi.quipu.auth.port.in;

import org.jspecify.annotations.Nullable;

/** Replaces the password of the authenticated account (RF-12). */
public interface ChangePasswordUseCase {

  /**
   * Changes the password and clears the pending-change flag of the account.
   *
   * @throws WeakPasswordException the new password is shorter than 8 characters or longer than
   *     bcrypt can hash
   * @throws PasswordUnchangedException the new password is the same as the current one
   * @throws com.tarimatwasi.quipu.auth.port.in.InvalidCredentialsException unknown account, or the
   *     current password is wrong, or missing when the account is not in the forced flow
   * @throws com.tarimatwasi.quipu.auth.port.in.AccountDisabledException the account is inactive
   */
  ChangePasswordResult changePassword(ChangePasswordCommand command);

  /**
   * The change requested by the account's own session. The current password is optional only while
   * the account must change it (the first login with a temporary password).
   */
  record ChangePasswordCommand(
      String userId, @Nullable String currentPassword, String newPassword) {}

  /** The fresh session of the account, which no longer must change its password. */
  record ChangePasswordResult(String sessionToken) {}
}
