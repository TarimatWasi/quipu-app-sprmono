package com.tarimatwasi.quipu.auth.port.out;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import java.util.Collection;
import java.util.List;

/** Storage of the administrators that another administrator creates (RF-22). */
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
   * Serializes the creation of administrators until the transaction ends, so two simultaneous
   * creations count one after the other and cannot exceed the maximum together. Needs a
   * transaction.
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
}
