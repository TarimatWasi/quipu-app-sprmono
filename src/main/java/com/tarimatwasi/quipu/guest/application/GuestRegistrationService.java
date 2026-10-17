package com.tarimatwasi.quipu.guest.application;

import com.tarimatwasi.quipu.guest.application.GuestAccessIssuer.Issued;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestAccessUseCase;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestSummaryView;
import com.tarimatwasi.quipu.guest.port.out.GuestAccountPort;
import com.tarimatwasi.quipu.guest.port.out.GuestAccountPort.IssuedAccess;
import com.tarimatwasi.quipu.shared.access.AccessMode;
import java.util.Objects;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * RF-02, RF-11, RF-17. Not transactional on purpose: {@link GuestAccessIssuer} commits the guest,
 * its account and the access before the email is queued (best-effort, RN-19).
 */
@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02: admin use case, besides the URL rule
public class GuestRegistrationService implements ManageGuestAccessUseCase {

  private final GuestAccessIssuer issuer;
  private final GuestAccountPort accounts;

  GuestRegistrationService(GuestAccessIssuer issuer, GuestAccountPort accounts) {
    this.issuer = issuer;
    this.accounts = accounts;
  }

  @Override
  public Registration register(RegisterCommand command) {
    Issued issued = issuer.register(command);
    GuestSummaryView guest = Objects.requireNonNull(issued.guest());
    return new Registration(guest, issued.access() == null ? null : delivered(issued));
  }

  @Override
  public AccessView generateAccess(Long guestId, AccessMode mode) {
    return delivered(issuer.generate(guestId, mode));
  }

  private AccessView delivered(Issued issued) {
    IssuedAccess access = Objects.requireNonNull(issued.access());
    String email = issued.email();
    boolean sent = email != null && accounts.sendAccess(email, access);
    return new AccessView(
        access.mode(), access.secret(), access.expiresAt(), sent, access.shareMessage());
  }
}
