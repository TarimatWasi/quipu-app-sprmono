package com.tarimatwasi.quipu.auth.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    @Nullable Instant lockedUntil,
    @Nullable Instant passwordChangedAt) {

  public boolean isDisabled() {
    return "INACTIVE".equals(status);
  }

  /**
   * TAR-125: a token issued before the last password change is no longer valid. The JWT's issue
   * time has second precision, so the change is compared at the second it happened: the token
   * issued in that same request (the new session) stays valid.
   */
  public boolean issuedBeforePasswordChange(Instant issuedAt) {
    return passwordChangedAt != null
        && issuedAt.isBefore(passwordChangedAt.truncatedTo(ChronoUnit.SECONDS));
  }

  /** SEG-06: locked until a moment that has not passed yet. */
  public boolean isLockedAt(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }
}
