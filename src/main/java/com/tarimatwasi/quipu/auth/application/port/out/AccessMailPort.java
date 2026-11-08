package com.tarimatwasi.quipu.auth.application.port.out;

import com.tarimatwasi.quipu.shared.access.AccessMode;

/** Builds the activation link and emails the access of a guest (RF-11, RN-19). */
public interface AccessMailPort {

  /** The page of the frontend that reads the activation token. */
  String activationUrl(String token);

  /**
   * Hands the email to the provider (TAR-309: the designed message of {@code mode}, with the
   * activation link or the temporary password in {@code secret}) and returns at once; nothing the
   * provider does reaches the caller.
   *
   * @return whether the email was queued
   */
  boolean send(String toEmail, AccessMode mode, String secret);
}
