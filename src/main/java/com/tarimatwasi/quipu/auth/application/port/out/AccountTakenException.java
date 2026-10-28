package com.tarimatwasi.quipu.auth.application.port.out;

/** A unique rule of the accounts refused the new one; {@link #taken()} says which. */
public class AccountTakenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** What another account already has. */
  public enum Taken {
    DOCUMENT,
    EMAIL
  }

  private final Taken taken;

  /** Creates the exception with the cause the store reported. */
  public AccountTakenException(Taken taken, Throwable cause) {
    super(
        "Another account already has that " + taken.name().toLowerCase(java.util.Locale.ROOT),
        cause);
    this.taken = taken;
  }

  /** What another account already has. */
  public Taken taken() {
    return taken;
  }
}
