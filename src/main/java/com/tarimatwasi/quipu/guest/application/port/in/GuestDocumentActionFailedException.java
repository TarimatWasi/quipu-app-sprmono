package com.tarimatwasi.quipu.guest.application.port.in;

import java.io.Serial;

/**
 * The object of a deleted document left the store but the transaction that removes its row and
 * writes its evidence failed; the row stays and repeating the action completes it (RN-37).
 */
public class GuestDocumentActionFailedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception with the failure. */
  public GuestDocumentActionFailedException(Throwable cause) {
    super("The guest document action could not be recorded", cause);
  }
}
