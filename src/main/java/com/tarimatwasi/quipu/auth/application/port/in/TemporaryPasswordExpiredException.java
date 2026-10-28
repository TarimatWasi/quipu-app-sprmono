package com.tarimatwasi.quipu.auth.application.port.in;

/**
 * The temporary password was right but its 30 minutes are over (RN-32): the guest asks the
 * administrator for a new access. Only thrown when the password matched, so it never tells whether
 * a document exists.
 */
public class TemporaryPasswordExpiredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public TemporaryPasswordExpiredException() {
    super("The temporary password expired");
  }
}
