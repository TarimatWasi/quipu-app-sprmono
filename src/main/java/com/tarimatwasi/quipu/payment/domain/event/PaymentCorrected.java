package com.tarimatwasi.quipu.payment.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.math.BigDecimal;
import java.time.YearMonth;
import org.jspecify.annotations.Nullable;

/**
 * An administrator corrected the proofs of a payment under review (A5.2).
 *
 * @param contractId the contract of the period paid; null for a stay
 * @param stayId the stay paid; null for a period of a contract
 * @param environmentId where the guest lives for what is paid; null for a stay without one
 * @param period the month paid; null for a stay
 */
public record PaymentCorrected(
    EventMetadata metadata,
    Long paymentId,
    Long guestId,
    @Nullable Long contractId,
    @Nullable Long stayId,
    @Nullable Long environmentId,
    @Nullable YearMonth period,
    BigDecimal amount)
    implements DomainEvent {

  @Override
  public String subject() {
    return "payment/" + paymentId;
  }
}
