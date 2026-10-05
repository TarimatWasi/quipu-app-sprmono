package com.tarimatwasi.quipu.auth.port.in;

/** The session's account no longer exists or is disabled, so the session is no longer valid. */
public class NoActiveSessionException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public NoActiveSessionException() {
    super("The session no longer has an active account");
  }
}
