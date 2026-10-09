package com.tarimatwasi.quipu.auth.port.out;

import com.tarimatwasi.quipu.auth.domain.DocumentType;

/** Storage of the first ADMIN. */
public interface InitialAdminStorePort {

  /** The account to create: it must change its password before anything else (RF-12). */
  record NewAdmin(
      String email, DocumentType documentType, String documentNumber, String passwordHash) {}

  /** Number of ADMIN accounts that can still log in. */
  int countActiveAdmins();

  /**
   * Stores the account as an active ADMIN that must change its password.
   *
   * @throws AccountAlreadyExistsException if the email or the document number is taken
   */
  void insert(NewAdmin admin);
}
