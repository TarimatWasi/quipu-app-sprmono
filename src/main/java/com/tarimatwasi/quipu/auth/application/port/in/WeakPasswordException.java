package com.tarimatwasi.quipu.auth.application.port.in;

/**
 * The new password breaks the length rule (RNF-08: at least 8; bcrypt hashes at most 72 bytes) or
 * is the single-use credential presented to set it.
 */
public class WeakPasswordException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Which rule was broken. */
  public enum Reason {
    TOO_SHORT,
    TOO_LONG,
    /** It is the single-use link token or recovery code that was presented with it (TAR-36). */
    SINGLE_USE_CREDENTIAL
  }

  private final Reason reason;

  /**
   * Creates the exception for the broken side of the rule.
   *
   * @param reason which side of the length rule was broken
   */
  public WeakPasswordException(Reason reason) {
    super("Password rejected: " + reason);
    this.reason = reason;
  }

  /** Which side of the length rule was broken. */
  public Reason reason() {
    return reason;
  }
}
