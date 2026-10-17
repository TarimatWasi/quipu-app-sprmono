package com.tarimatwasi.quipu.auth.application;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.auth.port.out.AccessGrantRepositoryPort;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuestAccountService implements GuestAccountUseCase {

  private final UserRepositoryPort users;
  private final AccessGrantRepositoryPort grants;
  private final Clock clock;

  GuestAccountService(UserRepositoryPort users, AccessGrantRepositoryPort grants, Clock clock) {
    this.users = users;
    this.grants = grants;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Long, GuestAccountState> stateOf(Collection<Long> guestIds) {
    Map<Long, GuestAccountState> states = new HashMap<>();
    if (guestIds.isEmpty()) {
      return states;
    }
    Set<Long> withTemporaryPassword =
        grants.guestsWithLiveTemporaryPassword(guestIds, clock.instant());
    for (UserAccount account : users.findAllByGuestIds(guestIds)) {
      Long guestId = account.guestId();
      if (guestId != null) {
        // A usable credential: its own password, or a live temporary one. A live link does not
        // count: it is used on /activate, not to log in.
        boolean usable =
            !ResetCodeIssuer.neverChosePassword(account) || withTemporaryPassword.contains(guestId);
        states.put(
            guestId,
            new GuestAccountState(!account.isDisabled() && usable, !account.mustChangePassword()));
      }
    }
    return states;
  }

  @Override
  @Transactional
  public void setActive(Long guestId, boolean active) {
    users.setGuestAccountStatus(guestId, active ? "ACTIVE" : "INACTIVE");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Long> guestOf(Long accountId) {
    return users.findById(accountId).map(UserAccount::guestId);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean documentBelongsToAdministrator(String documentType, String documentNumber) {
    DocumentType type;
    try {
      type = DocumentType.valueOf(documentType);
    } catch (IllegalArgumentException unknownType) {
      return false;
    }
    return users
        .findByDocument(type, documentNumber)
        .filter(account -> account.role() == Role.ADMIN)
        .isPresent();
  }
}
