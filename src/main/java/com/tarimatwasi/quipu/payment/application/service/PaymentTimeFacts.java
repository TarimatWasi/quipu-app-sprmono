package com.tarimatwasi.quipu.payment.application.service;

import com.tarimatwasi.quipu.payment.domain.event.PaymentDue;
import com.tarimatwasi.quipu.payment.domain.event.PaymentOverdue;
import com.tarimatwasi.quipu.shared.events.PublishEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Facts that time alone makes true write nothing of their own: each method is the point where one
 * is stated. The id names the charge, so the sweep of every following day states the same fact
 * again and the audit trail and the bell keep it once.
 */
@Service
public class PaymentTimeFacts {

  /** The due date of the charge arrived, unpaid. */
  @Transactional
  @PublishEvent(
      type = PaymentDue.class,
      id = "(#contractId != null ? 'c' + #contractId + ':' + #period : 's' + #stayId)",
      args = {"#guestId", "#contractId", "#stayId", "#period", "#amount", "#dueDate"})
  public void due(
      Long guestId,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable YearMonth period,
      BigDecimal amount,
      LocalDate dueDate) {
    // The fact is the whole change.
  }

  /** The charge is overdue. */
  @Transactional
  @PublishEvent(
      type = PaymentOverdue.class,
      id = "(#contractId != null ? 'c' + #contractId + ':' + #period : 's' + #stayId)",
      args = {"#guestId", "#contractId", "#stayId", "#period", "#amount", "#dueDate"})
  public void overdue(
      Long guestId,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable YearMonth period,
      BigDecimal amount,
      LocalDate dueDate) {
    // The fact is the whole change.
  }
}
