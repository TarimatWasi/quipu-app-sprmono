package com.tarimatwasi.quipu.guest.adapter.out.account;

import com.tarimatwasi.quipu.auth.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.guest.port.out.GuestAccountPort;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The guest module asks the auth module, through its exposed port, about the guests' accounts. */
@Component
public class AuthGuestAccountAdapter implements GuestAccountPort {

  private final GuestAccountUseCase accounts;

  AuthGuestAccountAdapter(GuestAccountUseCase accounts) {
    this.accounts = accounts;
  }

  @Override
  public Map<Long, AccountState> stateOf(Collection<Long> guestIds) {
    Map<Long, AccountState> states = new HashMap<>();
    accounts
        .stateOf(guestIds)
        .forEach(
            (id, state) ->
                states.put(id, new AccountState(state.canLogIn(), state.passwordChosen())));
    return states;
  }
}
