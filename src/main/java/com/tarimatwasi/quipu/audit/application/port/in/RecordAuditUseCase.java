package com.tarimatwasi.quipu.audit.application.port.in;

import com.tarimatwasi.quipu.shared.events.DomainEvent;

/** Keeps every confirmed fact in the audit trail; recording the same event twice is a no-op. */
public interface RecordAuditUseCase {

  /** Appends the fact once, identified by its event id. */
  void record(DomainEvent event);
}
