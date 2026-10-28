package com.tarimatwasi.quipu.auth.port.out;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Storage of the administrators that another administrator creates (RF-22), lists, deactivates and
 * reactivates (TAR-127).
 */
public interface AdministratorStorePort {

  /**
   * The account to create: an active ADMIN that must choose its password (RF-12).
   *
   * @param passwordHash the hash of a random value nobody knows, so the account cannot log in until
   *     the person chooses its password with the link
   * @param createdBy the administrator who creates it
   */
  record NewAdministrator(
      DocumentType documentType,
      String documentNumber,
      String email,
      String passwordHash,
      Long createdBy) {}

  /**
   * An ADMIN account as stored.
   *
   * @param status ACTIVE or INACTIVE
   * @param createdAt when the account was created
   */
  record StoredAdministrator(
      Long id,
      DocumentType documentType,
      String documentNumber,
      String email,
      String status,
      Instant createdAt) {

    /** The rule of UserAccount.isDisabled(): only INACTIVE cannot log in. */
    public boolean isActive() {
      return !"INACTIVE".equals(status);
    }
  }

  /**
   * Serializes the creation, deactivation and reactivation of administrators until the transaction
   * ends, so two simultaneous changes count one after the other: they cannot exceed the maximum nor
   * leave no active administrator together. Needs a transaction.
   */
  void lockAdministrators();

  /** Every ADMIN account, whatever its status. */
  int countAdministrators();

  /**
   * Stores the account and returns its id.
   *
   * @throws AccountTakenException if another account has the document or the email
   */
  Long insert(NewAdministrator administrator);

  /** The emails of the ADMIN accounts that can still log in, but those of {@code excluded}. */
  List<String> activeAdministratorEmailsExcept(Collection<Long> excluded);

  /** Every ADMIN account, active and inactive, the oldest first. */
  List<StoredAdministrator> listAdministrators();

  /**
   * The ADMIN account with that id, its row locked against other changes until the transaction ends
   * (not against the references to it); empty if the id is unknown or of another role. Needs a
   * transaction.
   */
  Optional<StoredAdministrator> lockAdministrator(Long id);

  /**
   * Holds the row of the account as one that another row is going to reference (the lock its
   * foreign keys take) until the transaction ends: it waits for a change of that row halfway, but
   * no change of status waits for it. Needs a transaction.
   */
  void holdAsReference(Long id);

  /** The ADMIN accounts that can still log in. */
  int countActiveAdministrators();

  /**
   * Sets the status (ACTIVE or INACTIVE) of the ADMIN account and records who changed it.
   *
   * @param modifiedBy the administrator who makes the change
   */
  void setAdministratorStatus(Long id, String status, Long modifiedBy);
}
