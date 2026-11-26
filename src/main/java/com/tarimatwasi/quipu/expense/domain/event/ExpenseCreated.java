package com.tarimatwasi.quipu.expense.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.math.BigDecimal;
import java.time.YearMonth;

/** An administrator recorded an expense of the month. */
public record ExpenseCreated(
    EventMetadata metadata, Long expenseId, BigDecimal amount, YearMonth month)
    implements DomainEvent {

  @Override
  public String subject() {
    return "expense/" + expenseId;
  }
}
