package com.tarimatwasi.quipu.auth.application;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.in.AdministratorDocumentTakenException;
import com.tarimatwasi.quipu.auth.port.in.AdministratorEmailTakenException;
import com.tarimatwasi.quipu.auth.port.in.AdministratorLimitReachedException;
import com.tarimatwasi.quipu.auth.port.in.AdministratorReauthenticationException;
import com.tarimatwasi.quipu.auth.port.out.AccountTakenException;
import com.tarimatwasi.quipu.auth.port.out.AdministratorStorePort;
import com.tarimatwasi.quipu.auth.port.out.AdministratorStorePort.NewAdministrator;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database half of RF-22: re-authenticates the creator and stores the new administrator, in one
 * transaction. It is its own bean so that the transaction ends before any email is sent: the link
 * is issued for a committed account, and the connection is not held while the provider answers.
 */
@Service
class AdministratorRegistrar {

  /** What to register; the document is already normalized and the hash already computed. */
  record Registration(
      Long creatorId,
      DocumentType documentType,
      String documentNumber,
      String email,
      String currentPassword,
      String unusablePasswordHash) {}

  /** The new account and the email of who created it, for the notices. */
  record Registered(Long id, String creatorEmail) {}

  private final AdministratorReauthentication reauthentication;
  private final AdministratorStorePort store;
  private final int maxAdministrators;

  AdministratorRegistrar(
      AdministratorReauthentication reauthentication,
      AdministratorStorePort store,
      @Value("${app.administrators.max:5}") int maxAdministrators) {
    if (maxAdministrators < 1) {
      throw new IllegalArgumentException("app.administrators.max must be at least 1");
    }
    this.reauthentication = reauthentication;
    this.store = store;
    this.maxAdministrators = maxAdministrators;
  }

  /**
   * The failed attempt is saved even though the creation ends in an exception, hence the {@code
   * noRollbackFor} ({@link AdministratorReauthentication} joins this transaction). Lock order: the
   * row of the creator (as a login of it), then the lock of the administrators, so the slow
   * password check never holds the latter.
   */
  @Transactional(noRollbackFor = AdministratorReauthenticationException.class)
  Registered register(Registration registration) {
    UserAccount creator =
        reauthentication.reauthenticate(registration.creatorId(), registration.currentPassword());
    store.lockAdministrators();
    if (store.countAdministrators() >= maxAdministrators) {
      throw new AdministratorLimitReachedException();
    }
    Long id;
    try {
      id =
          store.insert(
              new NewAdministrator(
                  registration.documentType(),
                  registration.documentNumber(),
                  registration.email(),
                  registration.unusablePasswordHash(),
                  creator.id()));
    } catch (AccountTakenException e) {
      throw switch (e.taken()) {
        case DOCUMENT -> new AdministratorDocumentTakenException();
        case EMAIL -> new AdministratorEmailTakenException();
      };
    }
    return new Registered(id, Objects.requireNonNull(creator.email()));
  }
}
