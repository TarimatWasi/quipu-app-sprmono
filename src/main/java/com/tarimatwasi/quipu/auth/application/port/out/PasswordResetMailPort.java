package com.tarimatwasi.quipu.auth.application.port.out;

import java.time.Duration;

/** Delivers the password recovery email. */
public interface PasswordResetMailPort {

  /**
   * Sends the recovery link built from the code.
   *
   * @param validFor how long the code works, so the email can say it
   * @param activation the account is an administrator without name: the link opens the activation
   *     screen (A0.5, TAR-127) instead of the recovery one
   * @throws MailDeliveryException if the provider did not accept the email
   */
  void sendResetLink(String toEmail, String code, Duration validFor, boolean activation);

  /**
   * Sends the link a new administrator uses to choose its first password (RF-22) and waits for the
   * provider, so the caller learns whether it left.
   *
   * @param validFor how long the code works, so the email can say it
   * @return true if the provider accepted the email
   */
  boolean sendAccessLinkNow(String toEmail, String code, Duration validFor);
}
