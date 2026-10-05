package com.tarimatwasi.quipu.auth.adapter.out.persistence;

import com.tarimatwasi.quipu.shared.domain.AuditableEntity;
import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "users")
public class UserJpaEntity extends AuditableEntity {

  @Id private UUID id;

  private String email;

  @Column(name = "document_type")
  @Enumerated(EnumType.STRING)
  private com.tarimatwasi.quipu.auth.domain.DocumentType documentType;

  @Column(name = "document_number")
  private String documentNumber;

  @Column(name = "password_hash")
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  private com.tarimatwasi.quipu.auth.domain.Role role;

  @Column(name = "guest_id")
  private UUID guestId;

  @Column(name = "must_change_password")
  private boolean mustChangePassword;

  private String status;

  @Column(name = "failed_login_attempts")
  private short failedLoginAttempts;

  @Column(name = "locked_until")
  private @Nullable Instant lockedUntil;

  @Column(name = "reset_token_hash")
  private @Nullable String resetTokenHash;

  @Column(name = "reset_token_expires_at")
  private @Nullable Instant resetTokenExpiresAt;

  protected UserJpaEntity() {}

  /** A new password also kills a recovery code that was emailed before it. */
  public void changePassword(String newPasswordHash) {
    this.passwordHash = newPasswordHash;
    this.mustChangePassword = false;
    this.resetTokenHash = null;
    this.resetTokenExpiresAt = null;
    clearFailedLogins();
  }

  /**
   * SEG-06: an expired lock restarts the count; reaching the limit locks the account. Returns true,
   * changing nothing, if the account is already locked.
   */
  public boolean registerFailedLogin(Instant now, int maxAttempts, Duration lockDuration) {
    if (lockedUntil != null) {
      if (lockedUntil.isAfter(now)) {
        return true;
      }
      clearFailedLogins();
    }
    failedLoginAttempts++;
    if (failedLoginAttempts >= maxAttempts) {
      lockedUntil = now.plus(lockDuration);
    }
    return false;
  }

  public void clearFailedLogins() {
    this.failedLoginAttempts = 0;
    this.lockedUntil = null;
  }

  public @Nullable Instant resetTokenExpiresAt() {
    return resetTokenExpiresAt;
  }

  public void replaceResetToken(String tokenHash, Instant expiresAt) {
    this.resetTokenHash = tokenHash;
    this.resetTokenExpiresAt = expiresAt;
  }

  public com.tarimatwasi.quipu.auth.domain.UserAccount toDomain() {
    return new com.tarimatwasi.quipu.auth.domain.UserAccount(
        id,
        email,
        documentType,
        documentNumber,
        passwordHash,
        role,
        guestId,
        mustChangePassword,
        status,
        failedLoginAttempts,
        lockedUntil);
  }
}
