package com.tarimatwasi.quipu.shared.masking;

import java.io.Serial;

/** A masked id that this service never issued for the kind asked, or that was altered. */
public class UnknownMaskedIdException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final IdKind kind;

  /** Creates the exception for the kind that was asked. */
  public UnknownMaskedIdException(IdKind kind) {
    super("Unknown masked id for " + kind);
    this.kind = kind;
  }

  /** The kind that was asked. */
  public IdKind kind() {
    return kind;
  }
}
