package com.tarimatwasi.quipu.payment.port.out;

/** The file store (R2) is not configured or did not answer. */
public class PaymentFileStorageException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the reason. */
  public PaymentFileStorageException(String message, Throwable cause) {
    super(message, cause);
  }

  /** Creates the exception when there is no underlying failure. */
  public PaymentFileStorageException(String message) {
    super(message);
  }
}
