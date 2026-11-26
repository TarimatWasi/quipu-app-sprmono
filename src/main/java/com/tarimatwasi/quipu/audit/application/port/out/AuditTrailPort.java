package com.tarimatwasi.quipu.audit.application.port.out;

import com.tarimatwasi.quipu.audit.domain.AuditRecord;

/** The append-only store of the audit trail (audit_event, insert-only by trigger and grants). */
public interface AuditTrailPort {

  /** Appends the entry; false if an entry with its id already exists (a redelivery). */
  boolean append(AuditRecord entry);
}
