package com.tarimatwasi.quipu.fine.port.out;

/** The base refused a fine because its guest is not in guests (the foreign key of V21). */
public class FineGuestMissingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception from the refusal of the base. */
  public FineGuestMissingException(Throwable cause) {
    super("The guest of the fine does not exist", cause);
  }
}
