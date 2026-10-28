package com.tarimatwasi.quipu.auth.application;

import com.tarimatwasi.quipu.auth.application.AdministratorStatusChanger.Changed;
import com.tarimatwasi.quipu.auth.port.in.LoginUseCase.DocumentKind;
import com.tarimatwasi.quipu.auth.port.in.ManageAdministratorsUseCase;
import com.tarimatwasi.quipu.auth.port.out.AdministratorNoticePort;
import com.tarimatwasi.quipu.auth.port.out.AdministratorNoticePort.StatusChange;
import com.tarimatwasi.quipu.auth.port.out.AdministratorStorePort;
import com.tarimatwasi.quipu.auth.port.out.AdministratorStorePort.StoredAdministrator;
import com.tarimatwasi.quipu.auth.port.out.MailDeliveryException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * TAR-127 (RF-22): lists the administrators and deactivates or reactivates another one, keeping at
 * least one active ADMIN. The session of a deactivated administrator stops working from its next
 * request on (the session rejects an INACTIVE account), so no token is revoked here.
 *
 * <p>Not transactional on purpose: the re-authentication commits first (a wrong password is counted
 * even though the change ends in an exception), then {@link AdministratorStatusChanger} changes the
 * status in its own transaction, and only then are the notices sent, so a failed email never undoes
 * the change.
 */
@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02: admin use case, besides the URL rule
public class AdministratorStatusService implements ManageAdministratorsUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(AdministratorStatusService.class);

  /** The characters of the document that the list shows, at its end. */
  private static final int VISIBLE_DOCUMENT_CHARACTERS = 3;

  private final AdministratorReauthentication reauthentication;
  private final AdministratorStatusChanger changer;
  private final AdministratorStorePort store;
  private final AdministratorNoticePort notices;

  AdministratorStatusService(
      AdministratorReauthentication reauthentication,
      AdministratorStatusChanger changer,
      AdministratorStorePort store,
      AdministratorNoticePort notices) {
    this.reauthentication = reauthentication;
    this.changer = changer;
    this.store = store;
    this.notices = notices;
  }

  @Override
  public List<AdministratorView> list(Long sessionUserId) {
    return store.listAdministrators().stream().map(a -> view(a, sessionUserId)).toList();
  }

  @Override
  public void deactivate(StatusChangeCommand command) {
    change(command, AdministratorStatus.INACTIVE);
  }

  @Override
  public void reactivate(StatusChangeCommand command) {
    change(command, AdministratorStatus.ACTIVE);
  }

  /** The password first: until it is confirmed, nothing tells whether the id exists. */
  private void change(StatusChangeCommand command, AdministratorStatus to) {
    reauthentication.reauthenticate(command.actorId(), command.currentPassword());
    Optional<Changed> changed = changer.change(command.actorId(), command.administratorId(), to);
    if (changed.isEmpty()) {
      return;
    }
    StatusChange change =
        to == AdministratorStatus.INACTIVE ? StatusChange.DEACTIVATED : StatusChange.REACTIVATED;
    LOG.info(
        "Administrator {} was {} by administrator {}",
        changed.get().id(),
        change == StatusChange.DEACTIVATED ? "deactivated" : "reactivated",
        command.actorId());
    notifyTheOthers(command.actorId(), changed.get(), change);
  }

  /**
   * Tells the other active administrators who changed which account, so a change nobody expected is
   * noticed. Best effort: a failed notice does not undo the change nor stop the next one.
   */
  private void notifyTheOthers(Long actorId, Changed changed, StatusChange change) {
    List<String> others;
    try {
      others = store.activeAdministratorEmailsExcept(List.of(actorId, changed.id()));
    } catch (DataAccessException e) {
      LOG.warn(
          "The other administrators were not told about administrator {} ({})",
          changed.id(),
          e.getClass().getSimpleName());
      return;
    }
    for (String other : others) {
      try {
        notices.administratorStatusChanged(
            other, changed.actorEmail(), changed.administratorEmail(), change);
      } catch (MailDeliveryException e) {
        // Only the type: the message of a provider can echo the address.
        LOG.warn(
            "An administrator was not told about administrator {} ({})",
            changed.id(),
            e.getClass().getSimpleName());
      }
    }
  }

  private static AdministratorView view(StoredAdministrator administrator, Long sessionUserId) {
    return new AdministratorView(
        administrator.id(),
        DocumentKind.valueOf(administrator.documentType().name()),
        maskDocument(administrator.documentNumber()),
        administrator.email(),
        administrator.isActive() ? AdministratorStatus.ACTIVE : AdministratorStatus.INACTIVE,
        administrator.createdAt(),
        administrator.id().equals(sessionUserId));
  }

  /** Only the last 3 characters are visible; a number that short is shown as it is. */
  static String maskDocument(String number) {
    int hidden = Math.max(0, number.length() - VISIBLE_DOCUMENT_CHARACTERS);
    return "*".repeat(hidden) + number.substring(hidden);
  }
}
