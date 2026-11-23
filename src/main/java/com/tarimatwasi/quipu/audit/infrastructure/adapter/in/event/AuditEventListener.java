package com.tarimatwasi.quipu.audit.infrastructure.adapter.in.event;

import com.tarimatwasi.quipu.audit.application.port.in.RecordAuditUseCase;
import com.tarimatwasi.quipu.shared.events.DomainEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Every domain event reaches the audit trail through the outbox: after the commit, on its own
 * transaction, retried until it completes. Idempotent by event id.
 */
@Component
public class AuditEventListener {
  private final RecordAuditUseCase audit;

  public AuditEventListener(RecordAuditUseCase audit) {
    this.audit = audit;
  }

  @ApplicationModuleListener
  public void on(DomainEvent event) {
    audit.record(event);
  }
}
