package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.mail;

/** The link of the recovery email: the page of the frontend that reads the code. */
final class ResetLink {

  private ResetLink() {}

  static String of(String frontendOrigin, String code) {
    return frontendOrigin.replaceAll("/+$", "") + "/reset-password?code=" + code;
  }

  /**
   * TAR-127 (A0.5): the link of an administrator without name opens the activation screen, where it
   * writes its full name, instead of the recovery one. The frontend route is {@code
   * /activate-admin}.
   */
  static String activation(String frontendOrigin, String code) {
    return frontendOrigin.replaceAll("/+$", "") + "/activate-admin?code=" + code;
  }
}
