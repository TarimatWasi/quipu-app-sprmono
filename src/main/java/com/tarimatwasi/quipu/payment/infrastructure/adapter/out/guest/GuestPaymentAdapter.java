package com.tarimatwasi.quipu.payment.infrastructure.adapter.out.guest;

import com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.LockGuestUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestSummaryView;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.NotActivatedView;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.PendingGuestView;
import com.tarimatwasi.quipu.guest.application.port.in.OwnGuestUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.OwnGuestUseCase.OwnGuestView;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** The payment module asks the guest module, through its exposed port, about its guests. */
@Component
public class GuestPaymentAdapter implements PaymentGuestPort {

  private final ManageGuestsUseCase guests;
  private final OwnGuestUseCase ownGuests;
  private final LockGuestUseCase locks;

  GuestPaymentAdapter(
      ManageGuestsUseCase guests, OwnGuestUseCase ownGuests, LockGuestUseCase locks) {
    this.guests = guests;
    this.ownGuests = ownGuests;
    this.locks = locks;
  }

  @Override
  public void lock(Long guestId) {
    locks.lock(guestId);
  }

  @Override
  public OwnGuest ownGuest(Long guestId) {
    OwnGuestView guest = ownGuests.own(guestId);
    return new OwnGuest(guest.id(), guest.type() == GuestKind.CONTRACT, standing(guest.status()));
  }

  private static Standing standing(GuestState status) {
    return switch (status) {
      case PENDING_ACTIVATION, ONBOARDING -> Standing.ONBOARDING;
      case ACTIVE -> Standing.ACTIVE;
      case INACTIVE -> Standing.INACTIVE;
    };
  }

  @Override
  public GuestProfile profileOf(Long guestId) {
    GuestSummaryView guest = guests.summariesOf(Set.of(guestId)).get(guestId);
    if (guest == null) {
      throw new GuestNotFoundException();
    }
    return profile(guest);
  }

  private static GuestProfile profile(GuestSummaryView guest) {
    return new GuestProfile(
        guest.id(),
        guest.name(),
        guest.documentNumber(),
        guest.type() == GuestKind.CONTRACT,
        guest.status() == GuestState.ACTIVE,
        guest.status() == GuestState.INACTIVE,
        guest.documentType().name());
  }

  @Override
  public NotActivated notActivated() {
    NotActivatedView found = guests.notActivated();
    PendingGuestView first = found.first();
    return new NotActivated(
        found.count(),
        found.expired(),
        first == null
            ? null
            : new PendingGuest(
                first.id(),
                first.documentType().name(),
                first.documentNumber(),
                first.accessExpiresAt()));
  }

  /** One read for the whole page; a guest that is gone is left out, the caller decides. */
  @Override
  public Map<Long, GuestProfile> profilesOf(Collection<Long> guestIds) {
    Map<Long, GuestProfile> profiles = new HashMap<>();
    guests.summariesOf(guestIds).forEach((id, guest) -> profiles.put(id, profile(guest)));
    return profiles;
  }
}
