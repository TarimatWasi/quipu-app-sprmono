package com.tarimatwasi.quipu.auth.application.port.out;

/** Builds the activation link and emails the access of a guest (RF-11, RN-19). */
public interface AccessMailPort {

  /** The page of the frontend that reads the activation token. */
  String activationUrl(String token);

  /**
   * Hands the email to the provider and returns at once; nothing the provider does reaches the
   * caller.
   *
   * @return whether the email was queued
   */
  boolean send(String toEmail, String subject, String text);
}
