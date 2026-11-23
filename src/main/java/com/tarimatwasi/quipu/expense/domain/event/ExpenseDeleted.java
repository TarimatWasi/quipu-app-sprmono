package com.tarimatwasi.quipu.expense.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/** An administrator deleted an expense. */
public record ExpenseDeleted(EventMetadata metadata, Long expenseId) implements DomainEvent {

  @Override
  public String subject() {
    return "expense/" + expenseId;
  }
}
