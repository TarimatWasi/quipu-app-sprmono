package com.tarimatwasi.quipu.auth.port.out;

/** Lets an administrator know that another administrator was created (RF-22). */
public interface AdministratorNoticePort {

  /**
   * Sends the notice in the background: who created which account, and nothing that gives access
   * (no link, code or document).
   *
   * @throws MailDeliveryException if the notice could not be handed over; the caller only logs it
   */
  void administratorCreated(String toEmail, String creatorEmail, String newAdministratorEmail);
}
