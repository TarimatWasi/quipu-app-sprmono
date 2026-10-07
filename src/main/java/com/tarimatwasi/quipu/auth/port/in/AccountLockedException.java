package com.tarimatwasi.quipu.auth.port.in;

import java.time.Instant;

/**
 * The account is temporarily locked after too many consecutive failed logins (SEG-06). It carries
 * when the lock ends and the whole seconds left, so the login can count down to it (TAR-131).
 */
public class AccountLockedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final Instant lockedUntil;
  private final long retryAfterSeconds;

  /**
   * Creates the exception.
   *
   * @param now the moment the login was refused
   * @param lockedUntil the instant the lock ends
   */
  public AccountLockedException(Instant now, Instant lockedUntil) {
    super("The account is temporarily locked");
    this.lockedUntil = lockedUntil;
    // Rounded up and never below one: a 0 would make the client retry at once and be refused again.
    long millisLeft = lockedUntil.toEpochMilli() - now.toEpochMilli();
    this.retryAfterSeconds = Math.max(1L, Math.ceilDiv(millisLeft, 1000L));
  }

  /** The instant the lock ends. */
  public Instant lockedUntil() {
    return lockedUntil;
  }

  /** Whole seconds until the account can try again, at least one. */
  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
