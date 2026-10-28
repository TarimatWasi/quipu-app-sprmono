package com.tarimatwasi.quipu.auth.application.port.in;

/** RF-11, RF-12, CU-24: the guest opens the activation link and chooses its own password. */
public interface ActivateAccessUseCase {

  /**
   * Checks the password, consumes the single-use token, sets the password and opens the session.
   *
   * @throws WeakPasswordException if the password does not meet the policy; the token is kept
   * @throws InvalidResetCodeException if the token is unknown, expired, revoked or already used
   * @throws AccountDisabledException if the account of the guest is disabled
   */
  ActivationResult activate(String token, String newPassword);

  /**
   * An activated access.
   *
   * @param guestId the guest the account belongs to
   * @param sessionToken the signed session the caller puts in the cookie
   */
  record ActivationResult(Long guestId, String sessionToken) {}
}
