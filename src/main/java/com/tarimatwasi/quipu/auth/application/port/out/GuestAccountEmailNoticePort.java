package com.tarimatwasi.quipu.auth.application.port.out;

/** TAR-229: tells the previous account email of a guest that the account email changed. */
public interface GuestAccountEmailNoticePort {

  /**
   * Sends the notice in the background, best-effort. The new email is only partly shown and nothing
   * gives access (no link, code or document).
   *
   * @throws MailDeliveryException if the notice could not be handed over; the caller only logs it
   */
  void accountEmailChanged(String previousEmail, String newEmail);
}
