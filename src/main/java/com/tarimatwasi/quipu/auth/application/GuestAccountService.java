package com.tarimatwasi.quipu.auth.application;

import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuestAccountService implements GuestAccountUseCase {

  private final UserRepositoryPort users;

  GuestAccountService(UserRepositoryPort users) {
    this.users = users;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Long, GuestAccountState> stateOf(Collection<Long> guestIds) {
    Map<Long, GuestAccountState> states = new HashMap<>();
    if (guestIds.isEmpty()) {
      return states;
    }
    for (UserAccount account : users.findAllByGuestIds(guestIds)) {
      Long guestId = account.guestId();
      if (guestId != null) {
        states.put(
            guestId, new GuestAccountState(!account.isDisabled(), !account.mustChangePassword()));
      }
    }
    return states;
  }
}
