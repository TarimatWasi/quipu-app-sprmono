package com.tarimatwasi.quipu.auth.application;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.port.in.PasswordRecoveryUseCase;
import com.tarimatwasi.quipu.auth.port.in.ProvisionInitialAdminUseCase;
import com.tarimatwasi.quipu.auth.port.out.AccountAlreadyExistsException;
import com.tarimatwasi.quipu.auth.port.out.InitialAdminStorePort;
import com.tarimatwasi.quipu.auth.port.out.InitialAdminStorePort.NewAdmin;
import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * No password ever travels through the environment, the repository or the hosting panel: the
 * account is created with the hash of random bytes that nobody knows, and the person chooses the
 * real password through the single-use link of the recovery flow (RF-16).
 */
@Service
public class InitialAdminService implements ProvisionInitialAdminUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(InitialAdminService.class);
  private static final int UNUSABLE_PASSWORD_BYTES = 32;

  private final SecureRandom random = new SecureRandom();
  private final InitialAdminStorePort store;
  private final PasswordEncoder passwordEncoder;
  private final PasswordRecoveryUseCase recovery;

  InitialAdminService(
      InitialAdminStorePort store,
      PasswordEncoder passwordEncoder,
      PasswordRecoveryUseCase recovery) {
    this.store = store;
    this.passwordEncoder = passwordEncoder;
    this.recovery = recovery;
  }

  @Override
  public boolean hasActiveAdmin() {
    return store.countActiveAdmins() > 0;
  }

  /** Not transactional on purpose: the account is committed before the link is requested. */
  @Override
  public void provision(InitialAdminCommand command) {
    if (hasActiveAdmin()) {
      return;
    }
    try {
      store.insert(
          new NewAdmin(
              command.email(), DocumentType.DNI, command.documentNumber(), unusablePasswordHash()));
    } catch (AccountAlreadyExistsException e) {
      if (!hasActiveAdmin()) {
        throw new IllegalStateException(
            "Cannot create the initial ADMIN: the email or the document number already belongs to"
                + " another user (not an active ADMIN)",
            e);
      }
      LOG.info("The initial ADMIN was created concurrently by another instance");
      return;
    }
    LOG.info("Initial ADMIN created without a password; a link to choose it is being sent");
    recovery.requestReset(command.email());
  }

  private String unusablePasswordHash() {
    byte[] bytes = new byte[UNUSABLE_PASSWORD_BYTES];
    random.nextBytes(bytes);
    return passwordEncoder.encode(Base64.getEncoder().encodeToString(bytes));
  }
}
