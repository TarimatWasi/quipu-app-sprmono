package com.tarimatwasi.quipu.auth.application.port.out;

import org.jspecify.annotations.Nullable;

/** The email provider did not accept the message. */
public class MailDeliveryException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MailDeliveryException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
