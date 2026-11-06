package com.tarimatwasi.quipu.auth.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record UserAccount(
    Long id,
    @Nullable String email,
    DocumentType documentType,
    String documentNumber,
    String passwordHash,
    Role role,
    @Nullable Long guestId,
    boolean mustChangePassword,
    String status,
    int failedLoginAttempts,
    @Nullable Instant lockedUntil,
    @Nullable Instant passwordChangedAt,
    long securityVersion) {

  /** Who this account is for the person who logs in: the type and number of the document. */
  public DocumentIdentity identity() {
    return new DocumentIdentity(documentType, documentNumber);
  }

  public boolean isDisabled() {
    return "INACTIVE".equals(status);
  }

  /** SEG-06: locked until a moment that has not passed yet. */
  public boolean isLockedAt(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }
}
