package com.tarimatwasi.quipu.auth.application.port.out;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** The email provider did not accept the message. */
public class MailDeliveryException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public MailDeliveryException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
