package com.tarimatwasi.quipu.auth.application.port.in;

import java.io.Serial;

/** RF-22: the maximum number of administrators already exists. */
public class AdministratorLimitReachedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final int maximum;

  /** Creates the exception with the configured maximum ({@code app.administrators.max}). */
  public AdministratorLimitReachedException(int maximum) {
    super("The maximum number of administrators already exists");
    this.maximum = maximum;
  }

  /** The configured maximum of administrators, which the message names. */
  public int maximum() {
    return maximum;
  }
}
