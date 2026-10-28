package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.mail;

/** The link of the recovery email: the page of the frontend that reads the code. */
final class ResetLink {

  private ResetLink() {}

  static String of(String frontendOrigin, String code) {
    return frontendOrigin.replaceAll("/+$", "") + "/reset-password?code=" + code;
  }
}
