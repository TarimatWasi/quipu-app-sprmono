package com.tarimatwasi.quipu.auth.port.in;

import com.tarimatwasi.quipu.auth.port.in.LoginUseCase.DocumentKind;

/**
 * RF-22, CU-30: an administrator creates another administrator. Administrators are equals, without
 * hierarchy. The account has no usable password: the person chooses it with a single-use link
 * emailed to the new account (the recovery flow of RF-16), so no password is ever handed over.
 */
public interface CreateAdministratorUseCase {

  /**
   * The new administrator and the re-authentication of the one who creates it.
   *
   * @param creatorId the account of the session that creates it
   * @param currentPassword the password of the creator, checked again before anything is created
   */
  record CreateAdministratorCommand(
      Long creatorId,
      DocumentKind documentType,
      String documentNumber,
      String email,
      String currentPassword) {}

  /**
   * The account that was created.
   *
   * @param emailSent whether the provider accepted the email with the link; if not, the account
   *     exists all the same and the person can use "forgot password" later
   */
  record AdministratorCreated(Long id, boolean emailSent) {}

  /**
   * Creates an active ADMIN that must choose its password, emails it the link to do so and lets the
   * other active administrators know.
   *
   * @throws InvalidAdministratorDocumentException if the number does not fit its type
   * @throws AdministratorReauthenticationException if {@code currentPassword} is not the password
   *     of the creator (or the creator's account is locked by failed attempts)
   * @throws AdministratorLimitReachedException if the maximum number of administrators exists
   * @throws AdministratorDocumentTakenException if another account has the document
   * @throws AdministratorEmailTakenException if another account has the email, ignoring case
   */
  AdministratorCreated create(CreateAdministratorCommand command);
}
