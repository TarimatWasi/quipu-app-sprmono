package com.tarimatwasi.quipu.payment.port.out;

import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import org.jspecify.annotations.Nullable;

/**
 * The period or the stay already has a live payment (the unique index of the table said so). The
 * status says which one, so that the answer is the right conflict.
 */
public class LivePaymentExistsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final @Nullable PaymentStatus liveStatus;

  /**
   * Creates the exception with the status of the payment that is already there, or {@code null} if
   * it was gone when it was looked for (the other request rolled back or rejected it meanwhile).
   */
  public LivePaymentExistsException(@Nullable PaymentStatus liveStatus) {
    super("A " + liveStatus + " payment already covers it");
    this.liveStatus = liveStatus;
  }

  /**
   * PENDING or APPROVED; {@code null} if the payment that blocked the insert is no longer there.
   */
  public @Nullable PaymentStatus liveStatus() {
    return liveStatus;
  }
}
