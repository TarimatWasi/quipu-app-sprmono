package com.tarimatwasi.quipu.auth.port.in;

/**
 * An account received more login attempts in a minute than SEC-01 allows (TAR-124), whatever the
 * address they came from. It is refused before the account is even read.
 */
public class TooManyLoginAttemptsException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final long retryAfterSeconds;

  /**
   * Creates the exception.
   *
   * @param retryAfterSeconds whole seconds until an attempt is allowed again, at least one
   */
  public TooManyLoginAttemptsException(long retryAfterSeconds) {
    super("Too many login attempts for the account");
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /** Whole seconds until an attempt is allowed again. */
  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
