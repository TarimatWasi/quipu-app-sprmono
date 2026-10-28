package com.tarimatwasi.quipu.auth.application;

import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.in.AdministratorNotFoundException;
import com.tarimatwasi.quipu.auth.port.in.AdministratorSelfDeactivationException;
import com.tarimatwasi.quipu.auth.port.in.LastActiveAdministratorException;
import com.tarimatwasi.quipu.auth.port.in.ManageAdministratorsUseCase.AdministratorStatus;
import com.tarimatwasi.quipu.auth.port.out.AdministratorStorePort;
import com.tarimatwasi.quipu.auth.port.out.AdministratorStorePort.StoredAdministrator;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database half of TAR-127: changes the status of an administrator in one transaction, under
 * the lock of the administrators that the creations take too (RF-22). It is its own bean so that
 * the transaction ends before any email is sent.
 *
 * <p>Lock order: the rows first, then the lock of the administrators, after which no row lock is
 * asked for. The rows are the account to change (against other changes) and the actor (as the one
 * the change will reference in last_modified_by_id); neither blocks the other's kind, so two
 * administrators who deactivate each other at once wait only for the lock of the administrators,
 * which makes the second count after the first. The creation locks the row of its creator, then the
 * same lock, so no two of them wait for each other in a circle.
 */
@Service
class AdministratorStatusChanger {

  /** The account that changed, and who changed it, for the notices. */
  record Changed(Long id, String actorEmail, String administratorEmail) {}

  private final UserRepositoryPort users;
  private final AdministratorStorePort store;

  AdministratorStatusChanger(UserRepositoryPort users, AdministratorStorePort store) {
    this.users = users;
    this.store = store;
  }

  /**
   * Sets the status of the administrator. The actor has already re-authenticated in its own
   * transaction, before the account was looked up; it is checked again under the lock, since
   * another administrator may have deactivated it meanwhile.
   *
   * @return the change, or empty if the account already had that status
   */
  @Transactional
  Optional<Changed> change(Long actorId, @Nullable Long administratorId, AdministratorStatus to) {
    if (administratorId == null) {
      throw new AdministratorNotFoundException();
    }
    if (to == AdministratorStatus.INACTIVE) {
      refuseSelfDeactivation(actorId, administratorId);
    }
    StoredAdministrator administrator =
        store.lockAdministrator(administratorId).orElseThrow(AdministratorNotFoundException::new);
    store.holdAsReference(actorId);
    store.lockAdministrators();
    UserAccount actor =
        users
            .findById(actorId)
            .orElseThrow(() -> new AccessDeniedException("The actor no longer exists"));
    AdministratorReauthentication.requireActiveAdministrator(actor);
    if (administrator.isActive() == (to == AdministratorStatus.ACTIVE)) {
      return Optional.empty();
    }
    // Counted in this transaction, under the lock: no other change of an administrator is halfway.
    if (to == AdministratorStatus.INACTIVE && store.countActiveAdministrators() <= 1) {
      throw new LastActiveAdministratorException();
    }
    store.setAdministratorStatus(administratorId, to.name(), actorId);
    return Optional.of(
        new Changed(administratorId, Objects.requireNonNull(actor.email()), administrator.email()));
  }

  /**
   * D-70 a: an administrator cannot deactivate their own account. The only place of the rule: to
   * allow it, remove this method and its call (the last active administrator is still kept).
   */
  private static void refuseSelfDeactivation(Long actorId, Long administratorId) {
    if (actorId.equals(administratorId)) {
      throw new AdministratorSelfDeactivationException();
    }
  }
}
