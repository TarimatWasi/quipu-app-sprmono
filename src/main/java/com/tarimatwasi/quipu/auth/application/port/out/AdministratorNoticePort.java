package com.tarimatwasi.quipu.auth.application.port.out;

/**
 * Lets an administrator know that another administrator was created (RF-22), deactivated or
 * reactivated (TAR-127).
 */
public interface AdministratorNoticePort {

  /** What happened to the account of an administrator. */
  enum StatusChange {
    DEACTIVATED,
    REACTIVATED
  }

  /**
   * Sends the notice in the background: who created which account, and nothing that gives access
   * (no link, code or document). Never throws: the adapter logs what it could not send.
   */
  void administratorCreated(String toEmail, String creatorEmail, String newAdministratorEmail);

  /**
   * Sends the notice in the background: who deactivated or reactivated which account, and nothing
   * that gives access (no link, code or document). Never throws: the adapter logs what it could not
   * send.
   */
  void administratorStatusChanged(
      String toEmail, String actorEmail, String administratorEmail, StatusChange change);
}
