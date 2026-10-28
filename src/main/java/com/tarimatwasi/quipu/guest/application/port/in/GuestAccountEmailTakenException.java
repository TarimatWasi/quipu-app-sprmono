package com.tarimatwasi.quipu.guest.application.port.in;

/**
 * TAR-229 (RF-02, D-76, D-77): another access account, administrator or guest, already has that
 * email as its account email, ignoring case; {@link #field()} says which input carried it.
 */
public class GuestAccountEmailTakenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The input that carried the email. */
  public enum Field {
    /** The contact email of a registration, which becomes the account email. */
    CONTACT_EMAIL,
    /** The account email the administrator sets. */
    EMAIL
  }

  private final Field field;

  /** Creates the exception with the cause the account module reported. */
  public GuestAccountEmailTakenException(Field field, Throwable cause) {
    super("Another account already has that email", cause);
    this.field = field;
  }

  /** The input that carried the email. */
  public Field field() {
    return field;
  }
}
