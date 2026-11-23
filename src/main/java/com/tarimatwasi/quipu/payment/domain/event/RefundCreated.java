package com.tarimatwasi.quipu.payment.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.math.BigDecimal;

/**
 * An administrator refunded part of the credit of a guest (RN-38).
 *
 * @param paymentId the payment whose credit the refund draws first
 * @param contract whether that payment is of a contract (false for a stay)
 */
public record RefundCreated(
    EventMetadata metadata,
    Long refundId,
    Long guestId,
    Long paymentId,
    boolean contract,
    BigDecimal amount)
    implements DomainEvent {

  @Override
  public String subject() {
    return "refund/" + refundId;
  }
}
