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
    @Nullable Instant passwordChangedAt) {

  /** Who this account is for the person who logs in: the type and number of the document. */
  public DocumentIdentity identity() {
    return new DocumentIdentity(documentType, documentNumber);
  }

  public boolean isDisabled() {
    return "INACTIVE".equals(status);
  }

  /**
   * TAR-125: a token issued before the last password change is no longer valid. TAR-201: the
   * comparison is exact, so the issue time must not be coarser than the truth on the early side
   * (the session layer rounds it up): the token issued in the same request as the change (the new
   * session) is issued after it and stays valid.
   */
  public boolean issuedBeforePasswordChange(Instant issuedAt) {
    return passwordChangedAt != null && issuedAt.isBefore(passwordChangedAt);
  }

  /** SEG-06: locked until a moment that has not passed yet. */
  public boolean isLockedAt(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }
}
