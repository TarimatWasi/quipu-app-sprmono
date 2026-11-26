package com.tarimatwasi.quipu.payment.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.jspecify.annotations.Nullable;

/**
 * A charge is overdue: its due date and grace days passed unpaid (RN-06). Stated once per charge.
 *
 * @param contractId the contract of the period; null for a stay
 * @param stayId the stay; null for a period of a contract
 * @param period the month charged; null for a stay
 */
public record PaymentOverdue(
    EventMetadata metadata,
    Long guestId,
    @Nullable Long contractId,
    @Nullable Long stayId,
    @Nullable YearMonth period,
    BigDecimal amount,
    LocalDate dueDate)
    implements DomainEvent {

  @Override
  public String subject() {
    return contractId != null ? "contract/" + contractId : "stay/" + stayId;
  }
}
