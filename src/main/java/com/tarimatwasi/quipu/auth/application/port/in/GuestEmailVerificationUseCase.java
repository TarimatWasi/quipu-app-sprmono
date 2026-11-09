package com.tarimatwasi.quipu.auth.application.port.in;

import java.util.Optional;

/**
 * TAR-349: a guest changes the one email of its account only after proving it owns the new one with
 * a code sent there. The current email stays until the code is confirmed.
 */
public interface GuestEmailVerificationUseCase {

  /**
   * Sends a code to the new email and keeps it pending, replacing any earlier pending request.
   *
   * @throws EmailVerificationException with {@code COOLDOWN} if a code was sent too recently, or
   *     {@code UNCHANGED} if the email is already the one of the account
   * @throws AccountEmailTakenException if another account has that email, ignoring case
   */
  void request(Long guestId, String email);

  /**
   * Makes the pending email the account email if the code matches, and tells the previous email.
   *
   * @throws EmailVerificationException with {@code INVALID_CODE} if the code does not match or
   *     nothing is pending, or {@code EXPIRED} if the code ran out of time or attempts
   * @throws AccountEmailTakenException if another account took that email meanwhile
   */
  void confirm(Long guestId, String code);

  /** The email waiting for its code, while its code can still be confirmed. */
  Optional<String> pendingEmail(Long guestId);
}
