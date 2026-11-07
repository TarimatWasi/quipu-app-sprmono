package com.tarimatwasi.quipu.auth.application.port.in;

import java.io.Serial;

/**
 * RF-22: the creator of an administrator did not confirm its own password, or its account is locked
 * by failed attempts (SEG-06).
 */
public class AdministratorReauthenticationException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public AdministratorReauthenticationException() {
    super("The current password of the administrator is not valid");
  }
}
