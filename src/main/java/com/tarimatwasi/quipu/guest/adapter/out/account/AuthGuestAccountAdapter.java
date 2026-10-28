package com.tarimatwasi.quipu.guest.adapter.out.account;

import com.tarimatwasi.quipu.auth.application.port.in.AccountDocumentTakenException;
import com.tarimatwasi.quipu.auth.application.port.in.GuestAccessUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.guest.port.out.GuestAccountPort;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentAlreadyExistsException;
import com.tarimatwasi.quipu.shared.access.AccessMode;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** The guest module asks the auth module, through its exposed port, about the guests' accounts. */
@Component
public class AuthGuestAccountAdapter implements GuestAccountPort {

  private final GuestAccountUseCase accounts;
  private final GuestAccessUseCase access;

  AuthGuestAccountAdapter(GuestAccountUseCase accounts, GuestAccessUseCase access) {
    this.accounts = accounts;
    this.access = access;
  }

  @Override
  public void openAccount(
      Long guestId, String documentType, String documentNumber, @Nullable String email) {
    try {
      access.openAccount(guestId, documentType, documentNumber, email);
    } catch (AccountDocumentTakenException e) {
      throw new GuestDocumentAlreadyExistsException(e);
    }
  }

  @Override
  public void changeAccountDocument(Long guestId, String documentType, String documentNumber) {
    try {
      access.changeAccountDocument(guestId, documentType, documentNumber);
    } catch (AccountDocumentTakenException e) {
      throw new GuestDocumentAlreadyExistsException(e);
    }
  }

  @Override
  public void revokeAccess(Long guestId) {
    access.revokeAccess(guestId);
  }

  @Override
  public IssuedAccess issueAccess(Long guestId, AccessMode mode) {
    var issued = access.issueAccess(guestId, mode);
    return new IssuedAccess(
        issued.mode(), issued.secret(), issued.expiresAt(), issued.shareMessage());
  }

  @Override
  public boolean sendAccess(String email, IssuedAccess issued) {
    return access.sendAccess(
        email,
        new GuestAccessUseCase.IssuedAccess(
            issued.mode(), issued.secret(), issued.expiresAt(), issued.shareMessage()));
  }

  @Override
  public Optional<Instant> accessExpiry(Long guestId) {
    return access.accessExpiry(guestId);
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

  @Override
  public void setActive(Long guestId, boolean active) {
    accounts.setActive(guestId, active);
  }

  @Override
  public boolean documentBelongsToAdministrator(String documentType, String documentNumber) {
    return accounts.documentBelongsToAdministrator(documentType, documentNumber);
  }
}
