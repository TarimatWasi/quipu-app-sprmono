package com.tarimatwasi.quipu.audit.application.service;

import com.tarimatwasi.quipu.audit.application.port.in.RecordAuditUseCase;
import com.tarimatwasi.quipu.audit.application.port.out.AuditTrailPort;
import com.tarimatwasi.quipu.audit.domain.AuditRecord;
import com.tarimatwasi.quipu.shared.events.DomainEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs on the outbox delivery, without a user: the fact was authorized by the use case that made
 * it, and the actor travels in its metadata.
 */
@Service
public class AuditService implements RecordAuditUseCase {
  private final AuditTrailPort trail;

  AuditService(AuditTrailPort trail) {
    this.trail = trail;
  }

  @Override
  @Transactional
  public void record(DomainEvent event) {
    trail.append(AuditRecord.of(event));
  }
}
