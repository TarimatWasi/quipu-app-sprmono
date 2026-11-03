package com.tarimatwasi.quipu.payment.application.port.in;

/** The file is not a JPG, PNG or PDF by content, or is over 5 MB (RF-15). */
public class InvalidVoucherFileException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String field;

  /** Creates the exception for the file of the form. */
  public InvalidVoucherFileException() {
    this("file");
  }

  /** Creates the exception naming the file, e.g. {@code proofs[1].file} (TAR-239). */
  public InvalidVoucherFileException(String field) {
    super("The file is not a JPG, PNG or PDF by content, or is over 5 MB (RF-15).");
    this.field = field;
  }

  /** The name of the file, as the contract names it. */
  public String field() {
    return field;
  }
}
