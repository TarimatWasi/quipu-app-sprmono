package com.tarimatwasi.quipu.auth.application.port.in;

/**
 * The first ADMIN of the system (ADR-F2). Nobody can create it from inside the application, so it
 * is provisioned at startup: the account has no usable password and the person chooses one with a
 * single-use link sent to the configured email (the recovery flow of RF-16).
 */
public interface ProvisionInitialAdminUseCase {

  /** The identity of the first ADMIN, who is identified by DNI. */
  record InitialAdminCommand(String documentNumber, String email) {}

  /** Whether at least one active ADMIN exists. */
  boolean hasActiveAdmin();

  /**
   * Creates the ADMIN and sends the link to choose its password, unless an active ADMIN already
   * exists (then it does nothing, so it is safe to call at every startup).
   *
   * @throws IllegalStateException if the email or document already belongs to another user, so no
   *     ADMIN can be created
   */
  void provision(InitialAdminCommand command);
}
