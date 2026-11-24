package com.tarimatwasi.quipu.auth.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.time.Instant;

/**
 * A recovery code was issued for an account (RF-10), the one the email carries. Only its expiry
 * travels, never the code: the trail shows who asked to recover an account, without letting anyone
 * use it. A request for an unknown or disabled account states nothing (ADR-F4).
 */
public record PasswordResetIssued(EventMetadata metadata, Long userId, Instant expiresAt)
    implements DomainEvent {

  @Override
  public String subject() {
    return "user/" + userId;
  }
}
