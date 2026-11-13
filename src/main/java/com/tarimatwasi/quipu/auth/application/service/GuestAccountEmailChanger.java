package com.tarimatwasi.quipu.auth.application.service;

import com.tarimatwasi.quipu.auth.application.port.in.AccountEmailTakenException;
import com.tarimatwasi.quipu.auth.application.port.in.GuestAccessUseCase.AccountEmailChange;
import com.tarimatwasi.quipu.auth.application.port.in.GuestAccessUseCase.EmailChangeOutcome;
import com.tarimatwasi.quipu.auth.application.port.out.AccountTakenException;
import com.tarimatwasi.quipu.auth.application.port.out.GuestAccountEmailNoticePort;
import com.tarimatwasi.quipu.auth.application.port.out.MailDeliveryException;
import com.tarimatwasi.quipu.auth.application.port.out.UserRepositoryPort;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.shared.changes.ChangeSignals;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The change of the account email of a guest (TAR-229), shared by the administrator (A3.9) and by
 * the guest once it verified the new email (TAR-349). It carries no authorization of its own: each
 * caller is a use case that checks its role. It runs in the transaction of the caller.
 */
@Service
class GuestAccountEmailChanger {

  private static final Logger LOG = LoggerFactory.getLogger(GuestAccountEmailChanger.class);

  private final ChangeSignals changes;
  private final UserRepositoryPort users;
  private final GuestAccountEmailNoticePort notices;

  GuestAccountEmailChanger(
      UserRepositoryPort users, GuestAccountEmailNoticePort notices, ChangeSignals changes) {
    this.changes = changes;
    this.users = users;
    this.notices = notices;
  }

  /**
   * The same address in other case is the same mailbox (the emails are unique ignoring case): it is
   * left as it is, and its recovery code keeps working. Any other change drops the recovery code.
   *
   * @throws AccountEmailTakenException if another account has the email, ignoring case
   */
  AccountEmailChange change(Long guestId, String email) {
    Optional<UserAccount> account = users.findByGuestIdForUpdate(guestId);
    if (account.isEmpty()) {
      return new AccountEmailChange(EmailChangeOutcome.NO_ACCOUNT, null);
    }
    String previous = account.get().email();
    if (previous != null && previous.equalsIgnoreCase(email)) {
      return new AccountEmailChange(EmailChangeOutcome.UNCHANGED, null);
    }
    try {
      users.changeEmail(account.get().id(), email);
    } catch (AccountTakenException e) {
      throw new AccountEmailTakenException(e);
    }
    changes.changed(
        new ChangeSignals.Change(ChangeSignals.Kind.PROFILE, guestId, false, null, null));
    return new AccountEmailChange(EmailChangeOutcome.CHANGED, previous);
  }

  /** Tells the previous email about the change, best-effort. */
  void notice(String previousEmail, String newEmail) {
    try {
      notices.accountEmailChanged(previousEmail, newEmail);
    } catch (MailDeliveryException e) {
      // Only the type: the message of a provider can echo the address.
      LOG.warn(
          "The previous account email was not told about the change ({})",
          e.getClass().getSimpleName());
    }
  }
}
