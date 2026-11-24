package com.tarimatwasi.quipu.expense.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.math.BigDecimal;
import java.time.YearMonth;

/** An administrator replaced the data of an expense. */
public record ExpenseUpdated(
    EventMetadata metadata, Long expenseId, BigDecimal amount, YearMonth month)
    implements DomainEvent {

  @Override
  public String subject() {
    return "expense/" + expenseId;
  }
}
