package com.tarimatwasi.quipu.auth.application.port.in;

import com.tarimatwasi.quipu.auth.application.port.in.LoginUseCase.DocumentKind;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * RF-22, TAR-127: an administrator lists the administrators and deactivates or reactivates another
 * one. The system always keeps at least one active ADMIN.
 */
public interface ManageAdministratorsUseCase {

  /** ACTIVE, or INACTIVE: an inactive account cannot log in and its sessions stop working. */
  enum AdministratorStatus {
    ACTIVE,
    INACTIVE
  }

  /**
   * An administrator of the list.
   *
   * @param maskedDocumentNumber only the last 3 characters are visible, every other one is {@code
   *     *}
   * @param self whether it is the account of the session
   */
  record AdministratorView(
      Long id,
      DocumentKind documentType,
      String maskedDocumentNumber,
      String email,
      AdministratorStatus status,
      Instant createdAt,
      boolean self) {}

  /**
   * A deactivation or reactivation and the re-authentication of who makes it.
   *
   * @param actorId the account of the session
   * @param administratorId the account to change; null when the id of the path was never issued for
   *     an account, which answers as an administrator that does not exist, after the password
   * @param currentPassword the password of the actor, checked before the account to change is
   *     looked up
   */
  record StatusChangeCommand(Long actorId, @Nullable Long administratorId, String currentPassword) {

    /** The password never reaches a log through the record. */
    @Override
    public String toString() {
      return "StatusChangeCommand[actorId="
          + actorId
          + ", administratorId="
          + administratorId
          + "]";
    }
  }

  /** Every ADMIN account, active and inactive, the oldest first. */
  List<AdministratorView> list(Long sessionUserId);

  /**
   * Deactivates another administrator; deactivating an inactive one changes nothing. When the
   * account changes, the other active administrators are told (best effort).
   *
   * @throws AdministratorReauthenticationException if {@code currentPassword} is not the password
   *     of the actor (or its account is locked by failed attempts)
   * @throws AdministratorNotFoundException if the id is not of an ADMIN account
   * @throws AdministratorSelfDeactivationException if it is the account of the actor
   * @throws LastActiveAdministratorException if no active administrator would be left
   */
  void deactivate(StatusChangeCommand command);

  /**
   * Reactivates an inactive administrator; reactivating an active one changes nothing. When the
   * account changes, the other active administrators are told (best effort).
   *
   * @throws AdministratorReauthenticationException if {@code currentPassword} is not the password
   *     of the actor (or its account is locked by failed attempts)
   * @throws AdministratorNotFoundException if the id is not of an ADMIN account
   */
  void reactivate(StatusChangeCommand command);
}
