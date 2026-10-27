package com.tarimatwasi.quipu.auth.port.in;

/** RF-22: the maximum number of administrators already exists. */
public class AdministratorLimitReachedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public AdministratorLimitReachedException() {
    super("The maximum number of administrators already exists");
  }
}
