package com.tarimatwasi.quipu.auth.port.out;

import java.time.Duration;

/** Delivers the password recovery email. */
public interface PasswordResetMailPort {

  /**
   * Sends the recovery link built from the code.
   *
   * @param validFor how long the code works, so the email can say it
   * @throws MailDeliveryException if the provider did not accept the email
   */
  void sendResetLink(String toEmail, String code, Duration validFor);

  /**
   * Sends the link a new administrator uses to choose its first password (RF-22) and waits for the
   * provider, so the caller learns whether it left.
   *
   * @param validFor how long the code works, so the email can say it
   * @return true if the provider accepted the email
   */
  boolean sendAccessLinkNow(String toEmail, String code, Duration validFor);
}
