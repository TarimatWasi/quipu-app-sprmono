package com.tarimatwasi.quipu.payment.adapter.out.guest;

import com.tarimatwasi.quipu.guest.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.port.in.LockGuestUseCase;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestSummaryView;
import com.tarimatwasi.quipu.guest.port.in.OwnGuestUseCase;
import com.tarimatwasi.quipu.guest.port.in.OwnGuestUseCase.OwnGuestView;
import com.tarimatwasi.quipu.payment.port.out.PaymentGuestPort;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
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
    GuestSummaryView guest = guests.get(guestId).summary();
    return new GuestProfile(
        guest.id(),
        guest.name(),
        guest.documentNumber(),
        guest.type() == GuestKind.CONTRACT,
        guest.status() == GuestState.ACTIVE,
        guest.status() == GuestState.INACTIVE);
  }

  // ponytail: one call to the guest module per distinct guest of the page (at most 100); a batch
  // read in the guest module if a queue page ever needs more.
  @Override
  public Map<Long, GuestProfile> profilesOf(Collection<Long> guestIds) {
    Map<Long, GuestProfile> profiles = new HashMap<>();
    for (Long id : guestIds) {
      try {
        profiles.put(id, profileOf(id));
      } catch (GuestNotFoundException gone) {
        // left out: the caller decides what a payment without a guest means
      }
    }
    return profiles;
  }
}
