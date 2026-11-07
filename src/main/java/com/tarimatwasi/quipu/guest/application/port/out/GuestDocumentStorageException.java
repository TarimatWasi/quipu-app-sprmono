package com.tarimatwasi.quipu.guest.application.port.out;

import java.io.Serial;

/** The file store (R2) is not configured or did not answer. */
public class GuestDocumentStorageException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception with the reason. */
  public GuestDocumentStorageException(String message, Throwable cause) {
    super(message, cause);
  }

  /** Creates the exception when there is no underlying failure. */
  public GuestDocumentStorageException(String message) {
    super(message);
  }
}
