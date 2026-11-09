package com.tarimatwasi.quipu.auth.application.port.in;

import java.io.Serial;

/** TAR-349: a guest email verification that cannot go on; {@link #reason()} says why. */
public class EmailVerificationException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Why the verification was refused. */
  public enum Reason {
    /** The code does not match the pending one, or there is no pending verification. */
    INVALID_CODE,
    /** The pending code ran out of time or of attempts; a new one has to be requested. */
    EXPIRED,
    /** A code was sent too recently; {@link #retryAfterSeconds()} says how long to wait. */
    COOLDOWN,
    /** The email asked for is already the one of the account, ignoring case. */
    UNCHANGED
  }

  private final Reason reason;
  private final long retryAfterSeconds;

  /** Creates the exception for any reason but {@link Reason#COOLDOWN}. */
  public EmailVerificationException(Reason reason) {
    this(reason, 0);
  }

  private EmailVerificationException(Reason reason, long retryAfterSeconds) {
    super("Email verification refused: " + reason);
    this.reason = reason;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /** A request made before the wait since the last code ended. */
  public static EmailVerificationException cooldown(long retryAfterSeconds) {
    return new EmailVerificationException(Reason.COOLDOWN, Math.max(1, retryAfterSeconds));
  }

  /** Why the verification was refused. */
  public Reason reason() {
    return reason;
  }

  /** Seconds until another code may be requested; 0 unless the reason is a cooldown. */
  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
