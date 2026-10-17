package com.tarimatwasi.quipu.auth.port.in;

import org.jspecify.annotations.Nullable;

/** Replaces the password of the authenticated account (RF-12). */
public interface ChangePasswordUseCase {

  /**
   * Changes the password and clears the pending-change flag of the account.
   *
   * @throws WeakPasswordException the new password is shorter than 8 characters or longer than
   *     bcrypt can hash
   * @throws PasswordUnchangedException the new password is the current one or the temporary one
   * @throws com.tarimatwasi.quipu.auth.port.in.InvalidCredentialsException unknown account, or the
   *     current password is wrong, or missing when the account is not in the forced flow
   * @throws com.tarimatwasi.quipu.auth.port.in.AccountDisabledException the account is inactive
   */
  ChangePasswordResult changePassword(ChangePasswordCommand command);

  /**
   * The change requested by the account's own session. The current password is optional only for
   * the session opened with a live temporary password ({@code temporarySession}) and for an
   * administrator that has not replaced its initial password; any other session must give it.
   */
  record ChangePasswordCommand(
      String userId,
      @Nullable String currentPassword,
      String newPassword,
      boolean temporarySession) {

    /** A change from a session that was not opened with a temporary password. */
    public ChangePasswordCommand(
        String userId, @Nullable String currentPassword, String newPassword) {
      this(userId, currentPassword, newPassword, false);
    }
  }

  /** The fresh session of the account, which no longer must change its password. */
  record ChangePasswordResult(String sessionToken) {}
}
