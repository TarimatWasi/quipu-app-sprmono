package com.tarimatwasi.quipu.auth.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record UserAccount(
    UUID id,
    String email,
    DocumentType documentType,
    String documentNumber,
    String passwordHash,
    Role role,
    @Nullable UUID guestId,
    boolean mustChangePassword,
    String status,
    int failedLoginAttempts,
    @Nullable Instant lockedUntil) {

  public boolean isDisabled() {
    return "INACTIVE".equals(status);
  }

  /** SEG-06: locked until a moment that has not passed yet. */
  public boolean isLockedAt(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }
}
